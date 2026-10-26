package com.eaglenet.ubuntuuml

internal data class PtyWindowSize(val columns: Int, val rows: Int)

internal fun ptyWindowSize(columns: Int, rows: Int): PtyWindowSize =
    PtyWindowSize(columns.coerceAtLeast(1), rows.coerceAtLeast(1))
