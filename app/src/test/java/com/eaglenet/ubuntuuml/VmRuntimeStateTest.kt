package com.eaglenet.ubuntuuml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VmRuntimeStateTest {
    @Test
    fun terminalRequiresBothRootfsAndNativeRuntime() {
        val state = VmRuntimeState()

        state.update { it.copy(diskReady = true) }
        assertFalse(state.value.terminalAvailable)

        state.update { it.copy(nativeRuntimeAvailable = true) }
        assertTrue(state.value.terminalAvailable)

        state.update { it.copy(diskReady = false) }
        assertFalse(state.value.terminalAvailable)
    }

    @Test
    fun ramSelectionRoundsToSupported512MibSteps() {
        assertEquals(512, normalizeRamMiB(512))
        assertEquals(2048, normalizeRamMiB(1900))
        assertEquals(4096, normalizeRamMiB(5000))
    }

    @Test
    fun startingAndStoppingKeepTerminalAvailabilityAndLifecycleInSync() {
        val state = VmRuntimeState(
            VmUiState(diskReady = true, nativeRuntimeAvailable = true),
        )

        state.update { it.copy(running = true, consoleReady = false) }
        assertTrue(state.value.running)
        assertFalse(state.value.consoleReady)
        assertTrue(state.value.terminalAvailable)

        state.update { it.copy(consoleReady = true) }
        assertTrue(state.value.consoleReady)

        state.update { it.copy(running = false, consoleReady = false) }
        assertFalse(state.value.running)
        assertFalse(state.value.consoleReady)
    }
}
