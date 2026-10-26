package com.eaglenet.ubuntuuml

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UmlKernelCommandLineTest {
    private val rootfs = File("/data/uml/rootfs.ext4")
    private val stub = File("/data/lib/libuml-stub.so")
    private val share = File("/data/uml/share")

    @Test
    fun systemdBootUsesGuestConsoleAndGettyWithoutReplacingInit() {
        val args = buildUmlKernelCommandLine(rootfs, 2048, stub, share, rootShell = false)

        assertTrue(args.contains("con=none"))
        assertTrue(args.contains("con0=fd:0,fd:1"))
        assertTrue(args.contains("console=tty0"))
        assertTrue(args.contains("systemd.wants=console-getty.service"))
        assertFalse(args.contains("init=/bin/bash"))
    }

    @Test
    fun emergencyBootRetainsDirectBashPidOneWithoutStartingSystemdGetty() {
        val args = buildUmlKernelCommandLine(rootfs, 2048, stub, share, rootShell = true)

        assertTrue(args.contains("init=/bin/bash"))
        assertFalse(args.contains("systemd.wants=console-getty.service"))
        assertTrue(args.contains("con0=fd:0,fd:1"))
    }

    @Test
    fun launchArgumentsRetainDiskMemoryAndHostShareSettings() {
        val args = buildUmlKernelCommandLine(rootfs, 1536, stub, share, rootShell = false)

        assertTrue(args.contains("mem=1536M"))
        assertTrue(args.contains("ubd0=${rootfs.absolutePath}"))
        assertTrue(args.contains("stub_exe=${stub.absolutePath}"))
        assertTrue(args.contains("hostfs=${share.absolutePath}"))
        assertTrue(args.contains("rw"))
    }
}
