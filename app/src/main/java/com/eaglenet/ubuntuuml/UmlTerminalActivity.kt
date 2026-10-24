package com.eaglenet.ubuntuuml

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment

import org.connectbot.terminal.TerminalEmulatorFactory
import sh.haven.core.terminal.HavenTerminal

import java.io.*
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/**
 * Haven terminal renderer with the real UML guest console.
 * No simulated shell or fake Ubuntu prompt.
 */
class UmlTerminalActivity : ComponentActivity() {

    private val main = Handler(Looper.getMainLooper())

    private var session: UmlPtySession? = null

    @Volatile private var terminalCols = 80
    @Volatile private var terminalRows = 24

    private var displayOutput:
        ((ByteArray, Int) -> Unit)? = null

    private var running by mutableStateOf(false)
    private var importing by mutableStateOf(false)
    private var diskReady by mutableStateOf(false)

    private var status by mutableStateOf(
        "Import Ubuntu rootfs to enable the VM"
    )

    private val disk
        get() = File(filesDir, "uml/rootfs.ext4")

    private val selectDisk = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->

        if (uri != null && !importing && !running) {
            importing = true
            status = "Importing Ubuntu rootfs…"

            Thread {
                runCatching {
                    importRootfs(uri)
                }.fold(
                    onSuccess = {
                        main.post {
                            importing = false
                            diskReady = true
                            status = "Rootfs ready · not running"
                        }
                    },
                    onFailure = { error ->
                        main.post {
                            importing = false
                            status =
                                "Import failed: ${error.message}"
                        }
                    }
                )
            }.start()
        }
    }

    private fun unpackedStream(
        src: InputStream
    ): InputStream {

        val buffered = BufferedInputStream(src)

        buffered.mark(8)

        val bytes = ByteArray(4)
        val n = buffered.read(bytes)

        buffered.reset()

        val zipped =
            n >= 4 &&
            bytes[0] == 0x50.toByte() &&
            bytes[1] == 0x4b.toByte()

        val gzipped =
            n >= 2 &&
            bytes[0] == 0x1f.toByte() &&
            bytes[1] == 0x8b.toByte()

        if (gzipped) {
            return GZIPInputStream(buffered)
        }

        if (!zipped) {
            return buffered
        }

        val zip = ZipInputStream(buffered)

        while (true) {
            val entry = zip.nextEntry
                ?: error("ZIP contains no Ubuntu filesystem")

            val name = entry.name.lowercase()

            if (
                !entry.isDirectory &&
                (
                    name.endsWith(".ext4") ||
                    name.endsWith(".img") ||
                    name.endsWith(".gz")
                )
            ) {
                return if (name.endsWith(".gz")) {
                    GZIPInputStream(zip)
                } else {
                    zip
                }
            }
        }
    }

    private fun importRootfs(uri: Uri) {
        val dest = disk

        dest.parentFile?.mkdirs()

        val tmp = File(
            dest.parentFile,
            "rootfs.ext4.part"
        )

        try {
            contentResolver.openInputStream(uri).use { raw ->
                requireNotNull(raw) {
                    "Unable to open selected file"
                }

                unpackedStream(raw).use { source ->
                    FileOutputStream(tmp).use { target ->
                        source.copyTo(
                            target,
                            1024 * 1024
                        )
                    }
                }
            }

            require(
                tmp.length() > 128L * 1024L * 1024L
            ) {
                "Filesystem too small (${tmp.length()} bytes)"
            }

            if (!tmp.renameTo(dest)) {
                dest.delete()

                check(tmp.renameTo(dest)) {
                    "Could not install Ubuntu filesystem"
                }
            }

        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    private fun boot(ram: Int) {
        if (running || importing || !diskReady) {
            return
        }

        status = "Starting Ubuntu UML…"
        running = true

        val guest = UmlPtySession(
            this,
            disk,
            ram,
            output = { bytes, size ->
                main.post {
                    displayOutput?.invoke(bytes, size)
                }
            },
            exited = { code ->
                main.post {
                    running = false
                    session = null
                    status = "Guest exited (code $code)"
                }
            },
        )

        session = guest

        Thread {
            runCatching {
                guest.start(terminalRows, terminalCols)
            }.fold(
                onSuccess = {
                    main.post {
                        status =
                            "UML launched · waiting for guest login"
                    }
                },
                onFailure = { error ->
                    main.post {
                        if (session === guest) {
                            guest.close()
                            session = null
                            running = false
                            status =
                                "Boot failed: ${error.message}"
                        }
                    }
                }
            )
        }.start()
    }

    private fun stopGuest() {
        val guest = session

        session = null
        running = false

        guest?.close()

        status = "Guest stopped · filesystem preserved"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        diskReady =
            disk.isFile &&
            disk.length() > 128L * 1024L * 1024L

        if (diskReady) {
            status = "Rootfs ready · not running"
        }

        setContent {
            val settings = remember {
                getSharedPreferences("uml_settings", MODE_PRIVATE)
            }
            var ram by remember {
                mutableIntStateOf(settings.getInt("ram_mib", 2048))
            }
            LaunchedEffect(ram) {
                settings.edit().putInt("ram_mib", ram).apply()
            }

            var font by remember {
                mutableIntStateOf(13)
            }

            val dark = Color(0xff11131c)
            val fg = Color(0xffe2e6f3)

            val emulator = remember {
                TerminalEmulatorFactory.create(
                    autoDetectUrls = true,
                    initialRows = 24,
                    initialCols = 80,
                    defaultForeground = fg,
                    defaultBackground = dark,
                    enableAltScreen = true,
                    onKeyboardInput = {
                        session?.send(it)
                    },
                    onResize = { dims ->
                        terminalCols = dims.columns.coerceAtLeast(1)
                        terminalRows = dims.rows.coerceAtLeast(1)
                        session?.resize(terminalCols, terminalRows)
                    },
                    onBell = {},
                    maxScrollbackLines = 10000,
                )
            }

            DisposableEffect(emulator) {
                displayOutput = { bytes, size ->
                    emulator.writeInput(
                        bytes,
                        0,
                        size
                    )
                }

                onDispose {
                    displayOutput = null
                }
            }

            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = dark
                )
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(dark)
                        .imePadding()
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = 12.dp,
                                vertical = 6.dp
                            ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Ubuntu 24.04 · UML",
                            color = fg,
                            modifier = Modifier.weight(1f),
                            fontSize = 15.sp
                        )

                        TextButton(
                            onClick = {
                                startActivity(
                                    Intent(
                                        this@UmlTerminalActivity,
                                        MainActivity::class.java
                                    )
                                )
                            }
                        ) {
                            Text("VM settings")
                        }
                    }

                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(
                                rememberScrollState()
                            )
                            .padding(horizontal = 8.dp),
                        horizontalArrangement =
                            Arrangement.spacedBy(6.dp)
                    ) {
                        Button(
                            enabled =
                                diskReady &&
                                !running &&
                                !importing,
                            onClick = {
                                boot(ram)
                            }
                        ) {
                            Text("Start")
                        }

                        OutlinedButton(
                            enabled = running,
                            onClick = {
                                stopGuest()
                            }
                        ) {
                            Text("Stop")
                        }

                        OutlinedButton(
                            enabled =
                                !running &&
                                !importing,
                            onClick = {
                                selectDisk.launch(
                                    arrayOf("*/*")
                                )
                            }
                        ) {
                            Text("Import rootfs")
                        }

                        OutlinedButton(
                            onClick = {
                                font =
                                    (font - 1)
                                        .coerceAtLeast(8)
                            }
                        ) {
                            Text("A−")
                        }

                        OutlinedButton(
                            onClick = {
                                font =
                                    (font + 1)
                                        .coerceAtMost(28)
                            }
                        ) {
                            Text("A+")
                        }

                        OutlinedButton(
                            enabled = !running,
                            onClick = {
                                ram =
                                    if (ram >= 4096) {
                                        512
                                    } else {
                                        ram + 512
                                    }
                            }
                        ) {
                            Text("RAM ${ram}M")
                        }
                    }

                    Text(
                        status,
                        modifier = Modifier.padding(
                            start = 12.dp,
                            bottom = 6.dp
                        ),
                        fontSize = 12.sp,
                        color = Color(0xffa6b3cd)
                    )

                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {

                            HavenTerminal(
                                terminalEmulator = emulator,
                                modifier = Modifier.fillMaxSize(),
                                typeface = Typeface.MONOSPACE,
                                initialFontSize = font.sp,
                                onFontSizeChanged = { newSize ->
                                    font = newSize.value.toInt().coerceIn(8, 28)
                                },
                                backgroundColor = dark,
                                foregroundColor = fg,
                                keyboardEnabled = true,
                                onHyperlinkClick = { link ->
                                    val dest =
                                        if (link.contains("://")) {
                                            link
                                        } else {
                                            "https://$link"
                                        }

                                    val parsed =
                                        Uri.parse(dest)

                                    if (
                                        parsed.scheme in listOf(
                                            "https",
                                            "http"
                                        )
                                    ) {
                                        runCatching {
                                            startActivity(
                                                Intent(
                                                    Intent.ACTION_VIEW,
                                                    parsed
                                                )
                                            )
                                        }
                                    }
                                },
                            )

                    }

                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(
                                rememberScrollState()
                            )
                            .padding(4.dp),
                        horizontalArrangement =
                            Arrangement.spacedBy(4.dp)
                    ) {
                        val keys = listOf(
                            "ESC" to "\u001b",
                            "TAB" to "\t",
                            "CTRL-C" to "\u0003",
                            "CTRL-D" to "\u0004",
                            "CTRL-Z" to "\u001a",
                            "←" to "\u001b[D",
                            "↓" to "\u001b[B",
                            "↑" to "\u001b[A",
                            "→" to "\u001b[C",
                            "HOME" to "\u001b[H",
                            "END" to "\u001b[F",
                            "PGUP" to "\u001b[5~",
                            "PGDN" to "\u001b[6~",
                            "F1" to "\u001bOP",
                            "F2" to "\u001bOQ",
                            "F3" to "\u001bOR",
                            "F4" to "\u001bOS",
                        )

                        keys.forEach { (label, seq) ->
                            TextButton(
                                onClick = {
                                    session?.send(
                                        seq.toByteArray(
                                            Charsets.UTF_8
                                        )
                                    )
                                },
                                contentPadding =
                                    PaddingValues(
                                        horizontal = 8.dp,
                                        vertical = 2.dp
                                    )
                            ) {
                                Text(
                                    label,
                                    color = Color(0xffc3b2ff),
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        stopGuest()
        super.onDestroy()
    }
}
