package com.eaglenet.ubuntuuml

import androidx.compose.runtime.*
import org.connectbot.terminal.TerminalEmulator

/** Each tab holds a real independent PTY, not an in-guest screen manager. */
internal class GuestTabState(
    val id: Int,
    val emulator: TerminalEmulator,
    initialName: String,
) {
    var name by mutableStateOf(initialName)
    var connection by mutableStateOf<GuestPtySession?>(null)
    @Volatile var cancelled = false

    fun close() {
        cancelled = true
        connection?.close()
        connection = null
        emulator.close()
    }
}
