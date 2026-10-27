package com.eaglenet.ubuntuuml

import android.content.Context
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import sh.haven.core.local.PtyBridge
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Real PTY-backed UML process.
 * Preserves guest TTY semantics.
 */
class UmlPtySession(
    private val context: Context,
    private val rootfs: File,
    private val ramMiB: Int,
    private val rootShell: Boolean,
    private val output: (ByteArray, Int) -> Unit,
    private val exited: (Int) -> Unit,
    private val failed: (String) -> Unit,
) : Closeable {

    companion object {
        private val forkWorker =
            Executors.newSingleThreadExecutor { job ->
                Thread(job, "uml-fork-parent").apply {
                    isDaemon = true
                }
            }

        fun nativeRuntimeAvailable(context: Context): Boolean {
            val libs = File(context.applicationInfo.nativeLibraryDir)
            return listOf(
                "libuml-net.so",
                "libuml-passt.so",
                "libvmlinux.so",
                "libuml-stub.so",
                "libpty_bridge.so",
            ).all { File(libs, it).isFile }
        }
    }

    private val writeWorker =
        Executors.newSingleThreadExecutor { job ->
            Thread(job, "uml-pty-writer").apply {
                isDaemon = true
            }
        }

    @Volatile
    private var closed = false

    @Volatile
    private var pfd: ParcelFileDescriptor? = null

    @Volatile
    private var processPid = -1

    private val lifecycleLock = Any()
    private var readerFinished = false
    private var input: ParcelFileDescriptor.AutoCloseInputStream? = null
    private var writer: ParcelFileDescriptor.AutoCloseOutputStream? = null
    private val inputQueue = PtyInputQueue(
        executor = writeWorker,
        isClosed = { closed },
        write = { bytes ->
            writer?.write(bytes)
            writer?.flush()
        },
        failed = failed,
    )

    fun start(rows: Int = 24, cols: Int = 80) {
        check(!closed) { "UML session is already closed" }
        check(
            rootfs.isFile &&
            rootfs.length() > 128L * 1024L * 1024L
        ) {
            "Ubuntu rootfs is missing or incomplete"
        }

        val libs = File(context.applicationInfo.nativeLibraryDir)

        fun lib(name: String) =
            File(libs, name).absolutePath.also { path ->
                check(File(path).isFile) {
                    "Native component missing: $name"
                }
            }

        val net = lib("libuml-net.so")
        val passt = lib("libuml-passt.so")
        val kernel = lib("libvmlinux.so")
        val stub = lib("libuml-stub.so")

        val share =
            File(context.filesDir, "uml/share").apply {
                mkdirs()
            }

        val passtLog =
            File(context.cacheDir, "uml/passt.log").apply {
                parentFile?.mkdirs()
            }

        val argv = arrayOf(
            net,
            passt,
            passtLog.absolutePath,
            kernel,
            *buildUmlKernelCommandLine(
                rootfs,
                ramMiB,
                File(stub),
                share,
                rootShell = rootShell,
                ptyToken = GuestPtySession.authToken(context),
            ),
        )

        val env = arrayOf(
            "TMPDIR=${context.cacheDir.absolutePath}",
            "HOME=${context.filesDir.absolutePath}",
            "PATH=/system/bin:/vendor/bin:${libs.absolutePath}",
            "TERM=xterm-256color",
            "PASST_NO_SANDBOX=1",
            "PASST_TFWD=5951",
        )

        val response = forkWorker.submit<IntArray> {
            PtyBridge.nativeForkPty(
                net,
                argv,
                env,
                rows,
                cols,
                true
            )
        }.get()

        check(response.size == 2 && response[0] >= 0 && response[1] > 0) {
            "UML forkpty failed (errno=${response.getOrNull(1)})"
        }

        val childPid = response[1]
        val master = ParcelFileDescriptor.adoptFd(response[0])
        val inputPfd = try {
            ParcelFileDescriptor.dup(master.fileDescriptor)
        } catch (error: IOException) {
            killChild(childPid)
            closeDescriptor(master)
            PtyBridge.nativeWaitPid(childPid)
            throw error
        }
        val writerPfd = try {
            ParcelFileDescriptor.dup(master.fileDescriptor)
        } catch (error: IOException) {
            killChild(childPid)
            closeDescriptor(inputPfd)
            closeDescriptor(master)
            PtyBridge.nativeWaitPid(childPid)
            throw error
        }
        val childInput = ParcelFileDescriptor.AutoCloseInputStream(inputPfd)
        val childWriter = ParcelFileDescriptor.AutoCloseOutputStream(writerPfd)

        synchronized(lifecycleLock) {
            if (closed) {
                killChild(childPid)
                closeDescriptor(childInput)
                closeDescriptor(childWriter)
                closeDescriptor(master)
                PtyBridge.nativeWaitPid(childPid)
                return
            }
            processPid = childPid
            pfd = master
            input = childInput
            writer = childWriter
        }

        val reader = Thread({
            val buf = ByteArray(8192)

            try {
                while (!closed) {
                    val size = childInput.read(buf)

                    if (size <= 0) break

                    output(buf.copyOf(size), size)
                }
            } catch (error: IOException) {
                if (!closed) {
                    synchronized(lifecycleLock) {
                        if (processPid > 0 && !readerFinished) {
                            killChild(processPid)
                        }
                    }
                    failed("UML console read failed: ${error.message}")
                }
            } finally {
                synchronized(lifecycleLock) {
                    readerFinished = true
                }
                val code = PtyBridge.nativeWaitPid(childPid)
                val notifyExit = synchronized(lifecycleLock) {
                    processPid = -1
                    if (closed) {
                        false
                    } else {
                        closed = true
                        true
                    }
                }
                closeResources()
                if (notifyExit) exited(code)
            }
        }, "uml-pty-reader").apply {
            isDaemon = true
        }
        try {
            reader.start()
        } catch (error: RuntimeException) {
            close()
            PtyBridge.nativeWaitPid(childPid)
            throw error
        }
    }

    fun send(bytes: ByteArray) {
        inputQueue.send(bytes)
    }

    fun resize(cols: Int, rows: Int) {
        if (!closed) {
            pfd?.let {
                val size = ptyWindowSize(cols, rows)
                if (PtyBridge.nativeSetSize(
                        it.fd,
                        size.rows,
                        size.columns,
                    ) != 0 && !closed
                ) {
                    failed("Could not resize the UML console")
                }
            }
        }
    }

    override fun close() {
        synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            if (processPid > 0 && !readerFinished) {
                killChild(processPid)
            }
        }

        closeResources()
    }

    private fun closeResources() {
        input?.let(::closeDescriptor)
        writer?.let(::closeDescriptor)
        pfd?.let(::closeDescriptor)

        writeWorker.shutdownNow()
    }

    private fun closeDescriptor(descriptor: java.io.Closeable) {
        try {
            descriptor.close()
        } catch (_: IOException) {
            // Shutdown closes several duplicated PTY descriptors at once.
        }
    }

    private fun killChild(pid: Int) {
        try {
            Os.kill(-pid, OsConstants.SIGKILL)
        } catch (error: ErrnoException) {
            if (error.errno != OsConstants.ESRCH) {
                android.os.Process.killProcess(pid)
                failed("Could not stop the UML process group: ${error.message}")
            }
        }
    }
}
