package com.eaglenet.ubuntuuml

import android.content.Context
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/** A real PTY inside one running UML guest, not a screen multiplexer. */
internal class GuestPtySession(
    private val context: Context,
    private val onOutput: (ByteArray) -> Unit,
    private val onExit: () -> Unit,
) : Closeable {
    companion object {
        const val PORT = 5951
        fun authToken(context: Context): String {
            val prefs = context.getSharedPreferences("uml_settings", Context.MODE_PRIVATE)
            synchronized(this) {
                prefs.getString("guest_pty_token", null)?.takeIf { it.length == 64 }?.let { return it }
                val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
                val token = bytes.joinToString("") { "%02x".format(it) }
                check(prefs.edit().putString("guest_pty_token", token).commit())
                return token
            }
        }
    }

    private val closed = AtomicBoolean(false)
    private var socket: Socket? = null
    private var output: DataOutputStream? = null

    fun connect() {
        check(!closed.get())
        val connected = Socket()
        try {
            connected.connect(InetSocketAddress("127.0.0.1", PORT), 1500)
            connected.tcpNoDelay = true
            val writer = DataOutputStream(connected.getOutputStream())
            val input = DataInputStream(connected.getInputStream())
            socket = connected
            output = writer
            sendFrame(3, authToken(context).toByteArray(Charsets.US_ASCII))
            Thread({
                try {
                    while (!closed.get()) {
                        val op = input.readUnsignedByte()
                        val length = input.readInt()
                        if (length !in 0..65536) break
                        val payload = ByteArray(length)
                        input.readFully(payload)
                        if (op == 4) onOutput(payload) else if (op == 5) break
                    }
                } catch (_: Exception) {
                } finally {
                    close()
                    onExit()
                }
            }, "uml-guest-pty-reader").apply { isDaemon = true }.start()
        } catch (e: Exception) {
            connected.close()
            throw e
        }
    }

    @Synchronized private fun sendFrame(op: Int, bytes: ByteArray) {
        if (closed.get()) return
        output?.writeByte(op)
        output?.writeInt(bytes.size)
        output?.write(bytes)
        output?.flush()
    }

    fun send(bytes: ByteArray) = sendFrame(0, bytes)
    fun resize(cols: Int, rows: Int) {
        val bytes = byteArrayOf((rows shr 8).toByte(), rows.toByte(), (cols shr 8).toByte(), cols.toByte())
        sendFrame(1, bytes)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { socket?.close() }
            socket = null
            output = null
        }
    }
}
