package com.eaglenet.ubuntuuml

import android.content.Context
import android.os.ParcelFileDescriptor
import sh.haven.core.local.PtyBridge
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * Real PTY-backed UML process.
 * Preserves guest TTY semantics.
 */
class UmlPtySession(
    private val context: Context,
    private val rootfs: File,
    private val ramMiB: Int,
    private val output: (ByteArray, Int) -> Unit,
    private val exited: (Int) -> Unit,
) : Closeable {

    companion object {
        private val forkWorker =
            Executors.newSingleThreadExecutor { job ->
                Thread(job, "uml-fork-parent").apply {
                    isDaemon = true
                }
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

    private var input: FileInputStream? = null
    private var writer: FileOutputStream? = null

    fun start(rows: Int = 24, cols: Int = 80) {
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
            "mem=${ramMiB}M",
            "ubd0=${rootfs.absolutePath}",
            "root=/dev/ubda",
            "rw",
            "stub_exe=$stub",
            "hostfs=${share.absolutePath}",
        )

        val env = arrayOf(
            "TMPDIR=${context.cacheDir.absolutePath}",
            "HOME=${context.filesDir.absolutePath}",
            "PATH=/system/bin:/vendor/bin:${libs.absolutePath}",
            "TERM=xterm-256color",
            "PASST_NO_SANDBOX=1",
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

        check(response.size == 2 && response[0] >= 0) {
            "UML forkpty failed (errno=${response.getOrNull(1)})"
        }

        processPid = response[1]

        val master = ParcelFileDescriptor.adoptFd(response[0])
        pfd = master

        input = FileInputStream(master.fileDescriptor)
        writer = FileOutputStream(master.fileDescriptor)

        Thread({
            val buf = ByteArray(8192)

            try {
                while (!closed) {
                    val size = input?.read(buf) ?: -1

                    if (size <= 0) break

                    output(buf.copyOf(size), size)
                }
            } catch (_: Exception) {
                // Closing the PTY unblocks its reader.
            } finally {
                if (!closed) {
                    val code = runCatching {
                        PtyBridge.nativeWaitPid(processPid)
                    }.getOrDefault(-1)

                    exited(code)
                }
            }
        }, "uml-pty-reader").apply {
            isDaemon = true
        }.start()
    }

    fun send(bytes: ByteArray) {
        if (closed) return

        runCatching {
            writeWorker.execute {
                if (!closed) {
                    runCatching {
                        writer?.write(bytes)
                        writer?.flush()
                    }
                }
            }
        }
    }

    fun resize(cols: Int, rows: Int) {
        if (!closed) {
            pfd?.let {
                runCatching {
                    PtyBridge.nativeSetSize(
                        it.fd,
                        rows.coerceAtLeast(1),
                        cols.coerceAtLeast(1)
                    )
                }
            }
        }
    }

    override fun close() {
        if (closed) return

        closed = true

        if (processPid > 0) {
            runCatching {
                android.os.Process.killProcess(processPid)
            }
        }

        runCatching { input?.close() }
        runCatching { writer?.close() }
        runCatching { pfd?.close() }

        writeWorker.shutdownNow()
    }
}
