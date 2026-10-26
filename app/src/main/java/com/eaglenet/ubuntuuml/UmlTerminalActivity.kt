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
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult

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
import androidx.compose.ui.focus.FocusRequester

import org.connectbot.terminal.TerminalEmulatorFactory
import sh.haven.core.terminal.HavenKeyboardMode
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

    private var imeVisible = false
    private var restoreKeyboardOnResume = false
    private var keyboardRestoreTick by mutableIntStateOf(0)

    private val disk
        get() = File(filesDir, "uml/rootfs.ext4")

    private val settingsLauncher = registerForActivityResult(StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            when (result.data?.getStringExtra(VmSettingsAction.EXTRA_ACTION)) {
                VmSettingsAction.START -> boot(rootShell = false)
                VmSettingsAction.STOP -> stopGuest()
            }
        }
    }

    private val selectDisk = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->

        val state = ActiveVm.state.value
        if (uri != null && !state.importing && !state.running) {
            ActiveVm.state.update {
                it.copy(importing = true, status = "Importing Ubuntu rootfs…")
            }

            Thread {
                runCatching {
                    importRootfs(uri)
                }.fold(
                    onSuccess = {
                        main.post {
                            ActiveVm.state.update {
                                it.copy(
                                    importing = false,
                                    diskReady = true,
                                    diskBytes = disk.length(),
                                    nativeRuntimeAvailable =
                                        UmlPtySession.nativeRuntimeAvailable(this),
                                    status = "Rootfs ready · not running",
                                )
                            }
                        }
                    },
                    onFailure = { error ->
                        main.post {
                            ActiveVm.state.update {
                                it.copy(
                                    importing = false,
                                    status = "Import failed: ${error.message}",
                                )
                            }
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

    private fun boot(rootShell: Boolean) {
        val state = ActiveVm.state.value
        if (state.running || state.importing || !state.terminalAvailable) {
            return
        }

        ActiveVm.state.update {
            it.copy(
                running = true,
                consoleReady = false,
                rootShell = rootShell,
                status = if (rootShell) {
                    "Starting emergency Bash as PID 1…"
                } else {
                    "Starting Ubuntu systemd…"
                },
            )
        }

        lateinit var guest: UmlPtySession
        guest = UmlPtySession(
            this,
            disk,
            state.ramMiB,
            rootShell,
            output = { bytes, size ->
                main.post {
                    displayOutput?.invoke(bytes, size)
                }
            },
            exited = { code ->
                main.post {
                    if (session === guest) {
                        session = null
                        ActiveVm.state.update {
                            it.copy(
                                running = false,
                                consoleReady = false,
                                rootShell = false,
                                status = "Guest exited (code $code)",
                            )
                        }
                    }
                }
            },
            failed = { message ->
                main.post {
                    if (session === guest) {
                        ActiveVm.state.update {
                            it.copy(status = "UML console error: $message")
                        }
                    }
                }
            },
        )

        session = guest

        Thread {
            runCatching {
                guest.start(terminalRows, terminalCols)
                guest.resize(terminalCols, terminalRows)
            }.fold(
                onSuccess = {
                    main.post {
                        if (session === guest && ActiveVm.state.value.running) {
                            ActiveVm.state.update {
                                it.copy(
                                    consoleReady = true,
                                    status = if (rootShell) {
                                        "UML started · emergency Bash PID 1"
                                    } else {
                                        "UML started · systemd guest booting"
                                    },
                                )
                            }
                        }
                    }
                },
                onFailure = { error ->
                    main.post {
                        if (session === guest) {
                            guest.close()
                            session = null
                            ActiveVm.state.update {
                                it.copy(
                                    running = false,
                                    consoleReady = false,
                                    rootShell = false,
                                    status = "Boot failed: ${error.message}",
                                )
                            }
                        }
                    }
                }
            )
        }.start()
    }

    private fun stopGuest() {
        val guest = session

        session = null
        guest?.close()

        ActiveVm.state.update {
            it.copy(
                running = false,
                consoleReady = false,
                rootShell = false,
                status = "Guest stopped · filesystem preserved",
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val preferences = getSharedPreferences("uml_settings", MODE_PRIVATE)
        val diskReady = disk.isFile && disk.length() > 128L * 1024L * 1024L
        ActiveVm.state.update { state ->
            state.copy(
                status = if (state.running) state.status else if (diskReady) {
                    "Rootfs ready · not running"
                } else {
                    "Import Ubuntu rootfs to enable the VM"
                },
                diskReady = diskReady,
                diskBytes = disk.takeIf { it.isFile }?.length() ?: 0,
                nativeRuntimeAvailable = UmlPtySession.nativeRuntimeAvailable(this),
                ramMiB = normalizeRamMiB(preferences.getInt("ram_mib", 2048)),
            )
        }

        setContent {
            val vmState = ActiveVm.state.value

            var font by remember {
                mutableIntStateOf(13)
            }
            val terminalFocus = remember { FocusRequester() }
            var terminalScrollController by remember {
                mutableStateOf<org.connectbot.terminal.ScrollController?>(null)
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
                                settingsLauncher.launch(
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
                            enabled = vmState.terminalAvailable &&
                                !vmState.running &&
                                !vmState.importing,
                            onClick = {
                                boot(rootShell = false)
                            }
                        ) {
                            Text("Systemd")
                        }

                        OutlinedButton(
                            enabled = vmState.terminalAvailable &&
                                !vmState.running &&
                                !vmState.importing,
                            onClick = {
                                boot(rootShell = true)
                            }
                        ) {
                            Text("Root shell")
                        }

                        OutlinedButton(
                            enabled = vmState.running,
                            onClick = {
                                stopGuest()
                            }
                        ) {
                            Text("Stop")
                        }

                        OutlinedButton(
                            enabled = !vmState.running && !vmState.importing,
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
                            enabled = !vmState.running && !vmState.importing,
                            onClick = {
                                val ramMiB = if (vmState.ramMiB >= 4096) {
                                    512
                                } else {
                                    vmState.ramMiB + 512
                                }
                                ActiveVm.state.update { it.copy(ramMiB = ramMiB) }
                                preferences.edit().putInt("ram_mib", ramMiB).apply()
                            }
                        ) {
                            Text("RAM ${vmState.ramMiB}M")
                        }
                    }

                    Text(
                        vmState.status,
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
                                keyboardEnabled = vmState.consoleReady,
                                showSoftKeyboard = vmState.consoleReady,
                                imeRestoreTick = keyboardRestoreTick,
                                keyboardMode = HavenKeyboardMode.Standard,
                                focusRequester = terminalFocus,
                                onTerminalTap = {
                                    terminalFocus.requestFocus()
                                    keyboardRestoreTick++
                                },
                                reflowOnKeyboard = true,
                                onScrollControllerAvailable = {
                                    terminalScrollController = it
                                },
                                onImeVisibilityChanged = { visible ->
                                    imeVisible = visible
                                    if (visible) terminalScrollController?.scrollToBottom()
                                },
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
                        TextButton(
                            enabled = vmState.consoleReady,
                            onClick = {
                                terminalFocus.requestFocus()
                                keyboardRestoreTick++
                            }
                        ) {
                            Text("⌨ Keyboard", color = Color(0xffc3b2ff))
                        }

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
                                enabled = vmState.consoleReady,
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

    override fun onPause() {
        restoreKeyboardOnResume = imeVisible
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        val preferences = getSharedPreferences("uml_settings", MODE_PRIVATE)
        if (!ActiveVm.state.value.running) {
            val ramMiB = normalizeRamMiB(preferences.getInt("ram_mib", 2048))
            ActiveVm.state.update { it.copy(ramMiB = ramMiB) }
        }
        if (restoreKeyboardOnResume) {
            restoreKeyboardOnResume = false
            keyboardRestoreTick++
        }
    }

    override fun onDestroy() {
        stopGuest()
        super.onDestroy()
    }
}
