package com.eaglenet.ubuntuuml

import java.io.IOException
import java.util.concurrent.Executor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PtyInputQueueTest {
    @Test
    fun forwardsAnImmutableCopyOfEachInputInSubmissionOrder() {
        val queued = mutableListOf<Runnable>()
        val written = mutableListOf<ByteArray>()
        val input = PtyInputQueue(
            executor = Executor { queued.add(it) },
            isClosed = { false },
            write = { written.add(it) },
            failed = { error("Unexpected write failure: $it") },
        )
        val first = byteArrayOf('e'.code.toByte(), 'x'.code.toByte())
        val second = byteArrayOf('i'.code.toByte(), 't'.code.toByte())

        input.send(first)
        input.send(second)
        first[0] = 'z'.code.toByte()
        queued.forEach { it.run() }

        assertEquals(2, written.size)
        assertArrayEquals(byteArrayOf('e'.code.toByte(), 'x'.code.toByte()), written[0])
        assertArrayEquals(byteArrayOf('i'.code.toByte(), 't'.code.toByte()), written[1])
    }

    @Test
    fun forwardsWriteFailuresToTheSession() {
        var failure: String? = null
        val input = PtyInputQueue(
            executor = Executor { it.run() },
            isClosed = { false },
            write = { throw IOException("broken PTY") },
            failed = { failure = it },
        )

        input.send(byteArrayOf(1))

        assertEquals("UML console write failed: broken PTY", failure)
    }
}
