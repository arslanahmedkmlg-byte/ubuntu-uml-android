package com.eaglenet.ubuntuuml

import java.io.File

internal fun buildUmlKernelCommandLine(
    rootfs: File,
    ramMiB: Int,
    stub: File,
    share: File,
    rootShell: Boolean,
    ptyToken: String? = null,
): Array<String> = arrayOf(
    "mem=${ramMiB}M",
    "ncpus=1",
    "ubd0=${rootfs.absolutePath}",
    "root=/dev/ubda",
    "rw",
    "stub_exe=${stub.absolutePath}",
    "hostfs=${share.absolutePath}",
    "con=none",
    "con0=fd:0,fd:1",
    "console=tty0",
    *(if (!rootShell && ptyToken != null) arrayOf("eaglenet_pty_token=$ptyToken") else emptyArray()),
    *(if (rootShell) {
        arrayOf("init=/bin/bash")
    } else {
        arrayOf("systemd.wants=console-getty.service")
    }),
)
