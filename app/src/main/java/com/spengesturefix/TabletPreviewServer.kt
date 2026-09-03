package com.spengesturefix

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.BufferedInputStream
import java.io.EOFException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Reverse preview channel for Tablet Mode.
 *
 * While the pen data flows phone -> PC on [TabletNetworkServer] (port 7654),
 * the PC client can connect to this port and stream back small JPEG frames of
 * its screen so the phone shows what the user is drawing on. The protocol is
 * intentionally trivial:
 *
 *     "#PV" + 8 hexadecimal digits of payload length + "\n" + JPEG bytes
 *
 * A decoder failure must never kill the socket thread: the frame is skipped
 * and the stream re-synchronizes on the next header.
 */
object TabletPreviewServer {
    private const val TAG = "TabletPreviewServer"
    const val PORT = 7655

    /** Header marker; the two nibbles after "#PV" encode the JPEG length. */
    private val HEADER = "#PV".toByteArray(Charsets.US_ASCII)

    /** Guards against a desynchronized stream growing the buffer forever. */
    private const val MAX_FRAME_BYTES = 3 * 1024 * 1024

    private val running = AtomicBoolean(false)
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var client: Socket? = null
    private val latestFrame = AtomicReference<Bitmap?>(null)
    private val framesReceived = AtomicLong(0L)
    private val frameWidth = AtomicInteger(0)
    private val frameHeight = AtomicInteger(0)
    private var serverThread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        framesReceived.set(0L)
        latestFrame.set(null)
        val thread = Thread(::serve, "TabletPreviewServer")
        thread.isDaemon = true
        serverThread = thread
        thread.start()
    }

    fun stop() {
        running.set(false)
        try { client?.close() } catch (_: Exception) { }
        try { serverSocket?.close() } catch (_: Exception) { }
        client = null
        serverSocket = null
        serverThread?.interrupt()
        serverThread = null
        latestFrame.set(null)
    }

    val isClientConnected: Boolean
        get() = client != null && running.get()

    fun framesReceived(): Long = framesReceived.get()

    /** Latest decoded preview frame, or null when nothing has arrived yet. */
    fun lastFrame(): Bitmap? = latestFrame.get()

    /** Width of the last decoded frame; the UI keeps the preview aspect correct. */
    fun frameWidth(): Int = frameWidth.get()

    fun frameHeight(): Int = frameHeight.get()

    private fun serve() {
        try {
            ServerSocket(PORT).also { serverSocket = it }.use { server ->
                server.reuseAddress = true
                while (running.get()) {
                    val socket = try {
                        server.accept()
                    } catch (_: Exception) {
                        break
                    }
                    if (!running.get()) {
                        try { socket.close() } catch (_: Exception) { }
                        break
                    }
                    socket.tcpNoDelay = true
                    client = socket
                    try {
                        readFrames(socket)
                    } finally {
                        if (client === socket) client = null
                        try { socket.close() } catch (_: Exception) { }
                    }
                }
            }
        } catch (error: Exception) {
            if (running.get()) Log.w(TAG, "Preview server stopped: ${error.message}")
        } finally {
            running.set(false)
            client = null
        }
    }

    private fun readFrames(socket: Socket) {
        socket.getInputStream().use { raw ->
            val input = BufferedInputStream(raw, 64 * 1024)
            val buffer = StringBuilder()
            val pending = java.io.ByteArrayOutputStream(MAX_FRAME_BYTES)
            while (running.get() && !socket.isClosed) {
                // A slow preview must never accumulate unbounded memory.
                if (buffer.length > MAX_FRAME_BYTES + HEADER.size + 9) {
                    buffer.setLength(0)
                    pending.reset()
                    continue
                }
                val byte = try {
                    input.read()
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (_: EOFException) {
                    break
                } ?: break
                if (byte < 0) break
                buffer.append(((byte and 0xFF).toChar()))
                if (buffer.length == HEADER.size + 8 &&
                    buffer.startsWith(String(HEADER, Charsets.US_ASCII))
                ) {
                    val length = buffer.substring(HEADER.size).toIntOrNull(16)
                    buffer.setLength(0)
                    if (length == null || length <= 0 || length > MAX_FRAME_BYTES) {
                        pending.reset()
                        continue
                    }
                    if (readFrame(input, length, pending)) {
                        framesReceived.incrementAndGet()
                    }
                } else if (buffer.length > HEADER.size + 8) {
                    // Not a header prefix; drop the oldest byte and rescan.
                    buffer.deleteCharAt(0)
                }
            }
        }
    }

    /** Reads exactly [length] bytes and decodes them; returns false on a bad frame. */
    private fun readFrame(input: BufferedInputStream, length: Int, pending: java.io.ByteArrayOutputStream): Boolean {
        pending.reset()
        val chunk = ByteArray(16 * 1024)
        var remaining = length
        while (remaining > 0 && running.get()) {
            val read = try {
                input.read(chunk, 0, chunk.size.coerceAtMost(remaining))
            } catch (_: Exception) {
                return false
            }
            if (read < 0) return false
            pending.write(chunk, 0, read)
            remaining -= read
        }
        if (remaining != 0) return false
        val payload = pending.toByteArray()
        return try {
            val bitmap = BitmapFactory.decodeByteArray(payload, 0, payload.size)
            if (bitmap == null) {
                false
            } else {
                frameWidth.set(bitmap.width)
                frameHeight.set(bitmap.height)
                latestFrame.set(bitmap)
                true
            }
        } catch (_: Exception) {
            false
        }
    }
}
