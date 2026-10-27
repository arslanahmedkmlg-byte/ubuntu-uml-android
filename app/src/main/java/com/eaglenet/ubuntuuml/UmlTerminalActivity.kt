package com.eaglenet.ubuntuuml

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

import org.connectbot.terminal.ModifierManager
import org.connectbot.terminal.SelectionController
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

    private var terminalTypeface by mutableStateOf(Typeface.MONOSPACE)

    private var imeVisible = false
    private var restoreKeyboardOnResume = false
    private var keyboardRestoreTick by mutableIntStateOf(0)

    private val disk
        get() = File(filesDir, "uml/rootfs.ext4")
    private val rootfsBackup
        get() = File(filesDir, "uml/rootfs.backup.ext4")

    private val settingsLauncher = registerForActivityResult(StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            when (result.data?.getStringExtra(VmSettingsAction.EXTRA_ACTION)) {
                VmSettingsAction.START -> boot(rootShell = false)
                VmSettingsAction.RECOVERY -> boot(rootShell = true)
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
                                    status = if (rootfsBackup.isFile) {
                                        "Rootfs ready · previous filesystem preserved in backup"
                                    } else "Rootfs ready · not running",
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
        check(!dest.exists() || !rootfsBackup.exists()) {
            "Rootfs backup already exists; export it before another replacement"
        }

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

            if (dest.exists()) {
                // Never delete the user's existing writable filesystem.
                // Retain one backup and refuse additional replacements until
                // the previous backup has been exported or handled explicitly.
                check(!rootfsBackup.exists()) {
                    "Rootfs backup already exists; export it before another replacement"
                }
                check(dest.renameTo(rootfsBackup)) {
                    "Could not preserve the existing Ubuntu filesystem"
                }
                if (!tmp.renameTo(dest)) {
                    check(rootfsBackup.renameTo(dest)) {
                        "Import failed; previous rootfs is safe at ${rootfsBackup.absolutePath}"
                    }
                    error("Could not install Ubuntu filesystem; previous rootfs restored")
                }
            } else {
                check(tmp.renameTo(dest)) { "Could not install Ubuntu filesystem" }
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

        // Recover an interrupted atomic rootfs swap on next launch.
        if (!disk.exists() && rootfsBackup.isFile) rootfsBackup.renameTo(disk)

        val preferences = getSharedPreferences("uml_settings", MODE_PRIVATE)
        val storedFont = preferences.getString("terminal_font_file", "JetBrainsMono-Regular.ttf")
            ?.takeIf { it in terminalFontFiles } ?: "JetBrainsMono-Regular.ttf"
        terminalTypeface = loadTerminalTypeface(assets, storedFont)
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
                mutableIntStateOf(preferences.getInt("terminal_font_sp", 11).coerceIn(9, 18))
            }
            var selectedFont by remember {
                mutableStateOf(preferences.getString("terminal_font_file", "JetBrainsMono-Regular.ttf")
                    ?.takeIf { it in terminalFontFiles } ?: "JetBrainsMono-Regular.ttf")
            }
            var fontMenuOpen by remember { mutableStateOf(false) }
            var sizeMenuOpen by remember { mutableStateOf(false) }
            var colorMenuOpen by remember { mutableStateOf(false) }
            var editingColor by remember { mutableStateOf<String?>(null) }
            var backgroundColor by remember {
                mutableStateOf(Color(preferences.getInt("terminal_background", 0xff000000.toInt())))
            }
            var foregroundColor by remember {
                mutableStateOf(Color(preferences.getInt("terminal_foreground", 0xffeeeeee.toInt())))
            }
            var keyboardChoice by remember {
                mutableStateOf(preferences.getString("keyboard_mode", "Secure") ?: "Secure")
            }
            var keyboardMenuOpen by remember { mutableStateOf(false) }
            var renameWindowOpen by remember { mutableStateOf(false) }
            var windowName by remember { mutableStateOf("") }
            var searchOpen by remember { mutableStateOf(false) }
            var searchQuery by remember { mutableStateOf("") }

            val havenKeyboardMode = when (keyboardChoice) {
                "Standard" -> HavenKeyboardMode.Standard
                "Raw" -> HavenKeyboardMode.Raw
                "SwiftKey compatible" -> HavenKeyboardMode.Custom(
                    sh.haven.core.terminal.ImeFlagSet(
                        noSuggestions = true,
                        visiblePassword = false,
                        autoCorrect = false,
                        fullEditor = false,
                    )
                )
                else -> HavenKeyboardMode.Secure
            }
            val terminalFocus = remember { FocusRequester() }
            val softwareKeyboard = LocalSoftwareKeyboardController.current
            var ctrlActive by remember { mutableStateOf(false) }
            var ctrlLocked by remember { mutableStateOf(false) }
            var altActive by remember { mutableStateOf(false) }
            var selectionController by remember { mutableStateOf<SelectionController?>(null) }
            val modifierManager = remember {
                object : ModifierManager {
                    override fun isCtrlActive() = ctrlActive
                    override fun isAltActive() = altActive
                    override fun isShiftActive() = false
                    override fun clearTransients() {
                        if (!ctrlLocked) ctrlActive = false
                        altActive = false
                    }
                }
            }
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
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
                    maxScrollbackLines = 25000,
                )
            }

            val tabs = remember { mutableStateListOf<GuestTabState>() }
            var activeTabId by remember { mutableIntStateOf(-1) }
            var nextTabId by remember { mutableIntStateOf(0) }
            val activeTab = tabs.firstOrNull { it.id == activeTabId }
            val activeEmulator = activeTab?.emulator ?: emulator
            val inputReady = vmState.consoleReady && (activeTab == null || activeTab.connection != null)
            LaunchedEffect(backgroundColor, foregroundColor, tabs.size) {
                emulator.setDefaultColors(foregroundColor.toArgb(), backgroundColor.toArgb())
                tabs.forEach { it.emulator.setDefaultColors(foregroundColor.toArgb(), backgroundColor.toArgb()) }
            }
            fun sendActive(bytes: ByteArray) {
                val guest = activeTab?.connection
                if (activeTab != null) guest?.send(bytes) else session?.send(bytes)
            }
            fun pasteClipboard() {
                val content = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this@UmlTerminalActivity)?.toString()
                if (!content.isNullOrEmpty()) sendActive(content.toByteArray(Charsets.UTF_8))
            }

            fun addTab() {
                val id = nextTabId++
                val terminal = TerminalEmulatorFactory.create(
                    autoDetectUrls = true, initialRows = 24, initialCols = 80,
                    defaultForeground = foregroundColor, defaultBackground = backgroundColor,
                    enableAltScreen = true,
                    onKeyboardInput = { bytes -> tabs.firstOrNull { it.id == id }?.connection?.send(bytes) },
                    onResize = { dims -> tabs.firstOrNull { it.id == id }?.connection
                        ?.resize(dims.columns.coerceAtLeast(1), dims.rows.coerceAtLeast(1)) },
                    onBell = {}, maxScrollbackLines = 25000,
                )
                val tab = GuestTabState(id, terminal, "Shell ${id + 1}")
                tabs.add(tab)
                activeTabId = id
                Thread({
                    for (attempt in 0..45) {
                        if (tab.cancelled) return@Thread
                        val connector = GuestPtySession(this@UmlTerminalActivity,
                            onOutput = { bytes -> main.post {
                                if (!tab.cancelled) tab.emulator.writeInput(bytes, 0, bytes.size)
                            } }, onExit = { main.post {
                                if (!tab.cancelled && tabs.contains(tab)) {
                                    tabs.remove(tab)
                                    tab.close()
                                    if (activeTabId == tab.id) activeTabId = tabs.lastOrNull()?.id ?: -1
                                }
                            } })
                        if (runCatching { connector.connect() }.isSuccess) {
                            main.post {
                                if (tab.cancelled) connector.close() else {
                                    tab.connection = connector
                                    connector.resize(terminalCols, terminalRows)
                                    ActiveVm.state.update { it.copy(status = "Ubuntu ready") }
                                }
                            }
                            return@Thread
                        }
                        connector.close()
                        Thread.sleep(1000)
                    }
                    main.post { if (!tab.cancelled) {
                        tabs.remove(tab); tab.close()
                        if (activeTabId == tab.id) activeTabId = tabs.lastOrNull()?.id ?: -1
                        ActiveVm.state.update { it.copy(status = "Guest PTY service unavailable") }
                    } }
                }, "uml-tab-connect-$id").start()
            }
            fun closeTab() {
                val closing = activeTab ?: return
                tabs.remove(closing)
                closing.close()
                activeTabId = tabs.lastOrNull()?.id ?: -1
            }
            LaunchedEffect(vmState.consoleReady, vmState.rootShell) {
                // The boot console is the first native terminal. Never create a
                // second emulator or initiate a guest socket while UML starts.
                // Additional independent PTYs are created only when + Tab is tapped.
                if (!vmState.running) {
                    tabs.toList().forEach { it.close() }
                    tabs.clear()
                    activeTabId = -1
                }
            }

            DisposableEffect(tabs) {
                onDispose { tabs.toList().forEach { it.close() } }
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
                                horizontal = 8.dp,
                                vertical = 4.dp
                            ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            activeTab?.let { "Ubuntu · ${it.name}" } ?: "Ubuntu 24.04 · UML",
                            color = fg,
                            modifier = Modifier.weight(1f),
                            fontSize = 15.sp
                        )

                        if (vmState.running) {
                            TextButton(onClick = { stopGuest() }) { Text("Stop") }
                        }

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

                    if (vmState.running && !vmState.rootShell) {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                                .padding(horizontal = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            FilterChip(
                                selected = activeTabId == -1,
                                onClick = { activeTabId = -1 },
                                label = { Text("Console") },
                            )
                            tabs.forEach { tab ->
                                FilterChip(
                                    selected = tab.id == activeTabId,
                                    onClick = { activeTabId = tab.id },
                                    label = { Text(tab.name, maxLines = 1) },
                                )
                            }
                            TextButton(onClick = { addTab() }) { Text("+ Tab") }
                        }
                    }

                    if (renameWindowOpen) {
                        AlertDialog(
                            onDismissRequest = { renameWindowOpen = false },
                            title = { Text("Rename guest tab") },
                            text = {
                                OutlinedTextField(
                                    value = windowName,
                                    onValueChange = { windowName = it.take(32) },
                                    label = { Text("Tab name") },
                                    singleLine = true,
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    val safeName = windowName.trim()
                                        .filter { it.isLetterOrDigit() || it in " _-." }
                                        .take(32)
                                    if (safeName.isNotEmpty()) activeTab?.name = safeName
                                    renameWindowOpen = false
                                }) { Text("Rename") }
                            },
                            dismissButton = {
                                TextButton(onClick = { renameWindowOpen = false }) { Text("Cancel") }
                            },
                        )
                    }

                    if (!vmState.running) {
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
                            Text("Start Ubuntu")
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

                    }

                    if (editingColor != null) {
                        val target = editingColor!!
                        TerminalColorWheelDialog(
                            title = target,
                            initial = if (target == "Terminal Color") backgroundColor else foregroundColor,
                            onCancel = { editingColor = null },
                            onPick = { color ->
                                if (target == "Terminal Color") {
                                    backgroundColor = color
                                    preferences.edit().putInt("terminal_background", color.toArgb()).apply()
                                } else {
                                    foregroundColor = color
                                    preferences.edit().putInt("terminal_foreground", color.toArgb()).apply()
                                }
                                editingColor = null
                            },
                        )
                    }

                    if (searchOpen) {
                        val matches = remember(searchQuery, activeEmulator) {
                            if (searchQuery.isBlank()) emptyList()
                            else activeEmulator.getSearchableLineTexts()
                                .withIndex()
                                .filter { it.value.contains(searchQuery, ignoreCase = true) }
                                .takeLast(100).asReversed()
                        }
                        AlertDialog(
                            onDismissRequest = { searchOpen = false },
                            title = { Text("Find in terminal") },
                            text = {
                                Column {
                                    OutlinedTextField(
                                        value = searchQuery,
                                        onValueChange = { searchQuery = it },
                                        label = { Text("Search output") },
                                        singleLine = true,
                                    )
                                    Text("${matches.size} recent matches")
                                    Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                                        matches.forEach { match ->
                                            TextButton(onClick = {
                                                terminalScrollController?.scrollToTop()
                                                terminalScrollController?.scrollBy(-match.index)
                                                searchOpen = false
                                            }) { Text("${match.index + 1}: ${match.value.trim().take(100)}") }
                                        }
                                    }
                                }
                            },
                            confirmButton = { TextButton(onClick = { searchOpen = false }) { Text("Done") } },
                        )
                    }

                    Text(
                        vmState.status,
                        modifier = Modifier.padding(
                            start = 8.dp,
                            bottom = 4.dp
                        ),
                        fontSize = 12.sp,
                        color = Color(0xffa6b3cd)
                    )

                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {

                            key(activeTabId) {
                            HavenTerminal(
                                terminalEmulator = activeEmulator,
                                modifier = Modifier.fillMaxSize(),
                                typeface = terminalTypeface,
                                initialFontSize = font.sp,
                                minFontSize = 9.sp,
                                maxFontSize = 18.sp,
                                onFontSizeChanged = { newSize ->
                                    font = newSize.value.toInt().coerceIn(9, 18)
                                    preferences.edit().putInt("terminal_font_sp", font).apply()
                                },
                                backgroundColor = backgroundColor,
                                foregroundColor = foregroundColor,
                                keyboardEnabled = inputReady,
                                showSoftKeyboard = inputReady,
                                imeRestoreTick = keyboardRestoreTick,
                                keyboardMode = havenKeyboardMode,
                                focusRequester = terminalFocus,
                                onTerminalTap = {
                                    terminalFocus.requestFocus()
                                    keyboardRestoreTick++
                                },
                                reflowOnKeyboard = false,
                                tapToPositionCursorOnPrompt = true,
                                modifierManager = modifierManager,
                                onSelectionControllerAvailable = { selectionController = it },
                                onPasteShortcut = { pasteClipboard() },
                                onPasteRequest = { pasteClipboard() },
                                onScrollControllerAvailable = {
                                    terminalScrollController = it
                                },
                                onImeVisibilityChanged = { visible ->
                                    imeVisible = visible
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

                        if (selectionController?.isSelectionActive == true) {
                            Surface(
                                modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                                tonalElevation = 8.dp,
                                color = Color(0xff303342)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = { selectionController?.copySelection() }) {
                                        Text("Copy")
                                    }
                                    TextButton(onClick = { pasteClipboard() }) {
                                        Text("Paste")
                                    }
                                    TextButton(onClick = { selectionController?.selectAll() }) {
                                        Text("Select all")
                                    }
                                    TextButton(onClick = { selectionController?.clearSelection() }) {
                                        Text("Close")
                                    }
                                }
                            }
                        }

                    }

                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        TextButton(enabled = vmState.consoleReady, onClick = {
                            val selected = selectionController?.getSelectedText().orEmpty()
                            if (selected.isNotEmpty()) {
                                clipboard.setPrimaryClip(ClipData.newPlainText("Terminal", selected))
                                selectionController?.clearSelection()
                            } else selectionController?.startSelection()
                        }) { Text("Copy") }
                        TextButton(enabled = vmState.consoleReady, onClick = { pasteClipboard() }) { Text("Paste") }
                        TextButton(enabled = vmState.consoleReady, onClick = {
                            selectionController?.selectAll()
                        }) { Text("Select All") }
                        TextButton(enabled = vmState.consoleReady, onClick = {
                            when {
                                !ctrlActive -> { ctrlActive = true; ctrlLocked = false }
                                !ctrlLocked -> ctrlLocked = true
                                else -> { ctrlActive = false; ctrlLocked = false }
                            }
                        }) { Text(if (ctrlLocked) "CTRL LOCK" else if (ctrlActive) "CTRL ON" else "CTRL") }
                        TextButton(enabled = vmState.consoleReady, onClick = { altActive = !altActive }) {
                            Text(if (altActive) "ALT ON" else "ALT")
                        }
                        TextButton(enabled = vmState.consoleReady, onClick = { searchOpen = true }) {
                            Text("Search")
                        }
                        TextButton(enabled = activeTab != null, onClick = { closeTab() }) { Text("Close tab") }
                        TextButton(enabled = activeTab != null, onClick = {
                            windowName = activeTab?.name.orEmpty()
                            renameWindowOpen = true
                        }) { Text("Rename tab") }
                        TextButton(enabled = vmState.consoleReady, onClick = {
                            if (imeVisible) softwareKeyboard?.hide()
                            else { terminalFocus.requestFocus(); keyboardRestoreTick++ }
                        }) { Text("⌨ Keyboard") }
                        Box {
                            TextButton(onClick = { fontMenuOpen = true }) { Text("Font") }
                            DropdownMenu(expanded = fontMenuOpen, onDismissRequest = { fontMenuOpen = false }) {
                                terminalFontFiles.forEach { file ->
                                    DropdownMenuItem(text = { Text(file.removeSuffix(".ttf")) }, onClick = {
                                        selectedFont = file
                                        terminalTypeface = loadTerminalTypeface(assets, file)
                                        preferences.edit().putString("terminal_font_file", file).apply()
                                        fontMenuOpen = false
                                    })
                                }
                            }
                        }
                        Box {
                            TextButton(onClick = { sizeMenuOpen = true }) { Text("${font}sp") }
                            DropdownMenu(expanded = sizeMenuOpen, onDismissRequest = { sizeMenuOpen = false }) {
                                (9..18).forEach { size ->
                                    DropdownMenuItem(text = { Text("${size}sp") }, onClick = {
                                        font = size
                                        preferences.edit().putInt("terminal_font_sp", font).apply()
                                        sizeMenuOpen = false
                                    })
                                }
                            }
                        }
                        Box {
                            TextButton(onClick = { colorMenuOpen = true }) { Text("Color") }
                            DropdownMenu(expanded = colorMenuOpen, onDismissRequest = { colorMenuOpen = false }) {
                                DropdownMenuItem(text = { Text("Terminal Color") }, onClick = {
                                    editingColor = "Terminal Color"; colorMenuOpen = false
                                })
                                DropdownMenuItem(text = { Text("Font Color") }, onClick = {
                                    editingColor = "Font Color"; colorMenuOpen = false
                                })
                            }
                        }
                        Box {
                            TextButton(onClick = { keyboardMenuOpen = true }) { Text(keyboardChoice) }
                            DropdownMenu(expanded = keyboardMenuOpen, onDismissRequest = { keyboardMenuOpen = false }) {
                                listOf("Secure", "SwiftKey compatible", "Standard", "Raw").forEach { mode ->
                                    DropdownMenuItem(text = { Text(mode) }, onClick = {
                                        keyboardChoice = mode
                                        preferences.edit().putString("keyboard_mode", mode).apply()
                                        keyboardMenuOpen = false
                                    })
                                }
                            }
                        }
                    }

                    Row(
                        Modifier.fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val keys = listOf(
                            "ESC" to "\u001b",
                            "TAB" to "\t",
                            "CTRL-C" to "\u0003",
                            "CTRL-D" to "\u0004",
                            "CTRL-Z" to "\u001a",
                            "CTRL-X" to "\u0018",
                            "CTRL-O" to "\u000f",
                            "←" to "\u001b[D",
                            "↓" to "\u001b[B",
                            "↑" to "\u001b[A",
                            "→" to "\u001b[C",
                            "HOME" to "\u001b[H",
                            "END" to "\u001b[F",
                            "PGUP" to "\u001b[5~",
                            "PGDN" to "\u001b[6~",
                            "DEL" to "\u001b[3~",
                            "INS" to "\u001b[2~",
                        )

                        keys.forEach { (label, seq) ->
                            TextButton(
                                enabled = vmState.consoleReady,
                                onClick = {
                                    sendActive(
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
