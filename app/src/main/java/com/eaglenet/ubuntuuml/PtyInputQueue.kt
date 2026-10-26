package com.eaglenet.ubuntuuml

import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

internal class PtyInputQueue(
    private val executor: Executor,
    private val isClosed: () -> Boolean,
    private val write: (ByteArray) -> Unit,
    private val failed: (String) -> Unit,
) {
    fun send(bytes: ByteArray) {
        if (isClosed()) return
        val copy = bytes.copyOf()
        try {
            executor.execute {
                if (!isClosed()) {
                    try {
                        write(copy)
                    } catch (error: IOException) {
                        if (!isClosed()) failed("UML console write failed: ${error.message}")
                    }
                }
            }
        } catch (error: RejectedExecutionException) {
            if (!isClosed()) failed("UML console writer is unavailable: ${error.message}")
        }
    }
}
