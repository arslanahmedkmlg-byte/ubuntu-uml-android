package com.eaglenet.ubuntuuml

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal data class VmUiState(
    val status: String = "Import Ubuntu rootfs to enable the VM",
    val running: Boolean = false,
    val consoleReady: Boolean = false,
    val importing: Boolean = false,
    val diskReady: Boolean = false,
    val diskBytes: Long = 0,
    val nativeRuntimeAvailable: Boolean = false,
    val ramMiB: Int = 2048,
    val rootShell: Boolean = false,
) {
    val terminalAvailable: Boolean
        get() = diskReady && nativeRuntimeAvailable
}

internal class VmRuntimeState(initial: VmUiState = VmUiState()) {
    var value by mutableStateOf(initial)
        private set

    fun update(transform: (VmUiState) -> VmUiState) {
        value = transform(value)
    }
}

internal object ActiveVm {
    val state = VmRuntimeState()
}

internal object VmSettingsAction {
    const val EXTRA_ACTION = "vm_settings_action"
    const val START = "start"
    const val STOP = "stop"
}

internal fun normalizeRamMiB(value: Int): Int =
    (((value + 256) / 512).coerceIn(1, 8)) * 512
