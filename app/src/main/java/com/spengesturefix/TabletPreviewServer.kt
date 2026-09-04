package com.spengesturefix

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.BufferedInputStream
import java.io.EOFException
import java.net.InetSocketAddress
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
 *     "#PV" + 8 hexadecimal digits of payload length + JPEG bytes
 *
 * Reliability rules (each one fixed a real-world disconnect or stall):
 *  - **Latest connection wins.** When the PC reconnects after a Wi-Fi hiccup,
 *    the zombie socket from the dead connection is evicted immediately
 *    instead of queueing the new client behind it forever.
 *  - **Decode off the socket thread.** BitmapFactory runs on its own thread;
 *    a slow decode can never apply TCP backpressure to the sender.
 *  - **Heartbeat.** While a client is attached, a one-byte "#" pings it every
 *    second, keeping the phone's Wi-Fi radio out of power save during quiet
 *    stretches (static screen) so motion bursts do not stutter or reset.
 *  - The reader consumes the socket in bulk (up to 64 KB per read) through a
 *    small state machine, so even a 60 fps stream of ~50 KB frames stays far
 *    below what the network can deliver.
 */
object TabletPreviewServer {
    private const val TAG = "TabletPreviewServer"
    const val PORT = 7655

    /** Header marker; the 8 characters after "#PV" encode the JPEG length. */
    private val HEADER = "#PV".toByteArray(Charsets.US_ASCII)

    /** Size of the framing header: "#PV" + 8 hex digits. */
    private val HEADER_BYTES = HEADER.size + 8

    /** Guards against a desynchronized stream growing the buffer forever. */
    private const val MAX_FRAME_BYTES = 3 * 1024 * 1024

    /** Bulk read chunk; large enough for several 50 KB frames per syscall. */
    private const val READ_CHUNK = 64 * 1024

    /** Wi-Fi power-save keepalive interval while a client is attached. */
    private const val HEARTBEAT_MS = 1000L

    private val running = AtomicBoolean(false)
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var client: Socket? = null
    private val latestFrame = AtomicReference<Bitmap?>(null)
    private val framesReceived = AtomicLong(0L)
    /** Bumped on every decoded frame; the UI polls it to recompose instantly. */
    private val frameSeq = AtomicLong(0L)
    private val frameWidth = AtomicInteger(0)
    private val frameHeight = AtomicInteger(0)
    private var serverThread: Thread? = null
    @Volatile private var heartbeatThread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        framesReceived.set(0L)
        frameSeq.set(0L)
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
        heartbeatThread?.interrupt()
        heartbeatThread = null
        latestFrame.set(null)
    }

    val isClientConnected: Boolean
        get() = client != null && running.get()

    fun framesReceived(): Long = framesReceived.get()

    /** Monotonic per-frame counter; changes whenever a new frame is decoded. */
    fun frameSeq(): Long = frameSeq.get()

    /** Latest decoded preview frame, or null when nothing has arrived yet. */
    fun lastFrame(): Bitmap? = latestFrame.get()

    /** Width of the last decoded frame; the UI keeps the preview aspect correct. */
    fun frameWidth(): Int = frameWidth.get()

    fun frameHeight(): Int = frameHeight.get()

    private fun serve() {
        try {
            ServerSocket().also { serverSocket = it }.use { server ->
                server.reuseAddress = true
                server.bind(InetSocketAddress(PORT))
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
                    // Latest connection wins: a reconnecting PC must not queue
                    // behind the zombie of a dead previous connection.
                    client?.let { previous ->
                        try { previous.close() } catch (_: Exception) { }
                        Log.i(TAG, "Evicted previous preview client for a new one")
                    }
                    socket.tcpNoDelay = true
                    client = socket
                    startHeartbeat(socket)
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

    /**
     * Pings the attached client once per second. The PC never sends on this
     * channel, so without this traffic a static screen is fully silent and
     * the phone's Wi-Fi radio drops into power save; the next motion burst
     * then stutters or resets the connection. One byte per second is free.
     */
    private fun startHeartbeat(socket: Socket) {
        heartbeatThread?.interrupt()
        val thread = Thread({
            val stream = try {
                socket.getOutputStream()
            } catch (_: Exception) {
                return@Thread
            }
            while (running.get() && client === socket && !socket.isClosed) {
                try {
                    stream.write('#'.code)
                    stream.flush()
                } catch (_: Exception) {
                    return@Thread
                }
                try {
                    Thread.sleep(HEARTBEAT_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }, "TabletPreviewHeartbeat")
        thread.isDaemon = true
        heartbeatThread = thread
        thread.start()
    }

    /** Reader phases: scanning for a header, then collecting a payload. */
    private const val PHASE_HEADER = 0
    private const val PHASE_PAYLOAD = 1

    private fun readFrames(socket: Socket) {
        socket.getInputStream().use { raw ->
            val input = BufferedInputStream(raw, 256 * 1024)
            val scratch = ByteArray(READ_CHUNK)
            val header = ByteArray(HEADER_BYTES)
            var headerFill = 0
            var phase = PHASE_HEADER
            var payload = ByteArray(0)
            var payloadLength = 0
            var payloadFill = 0
            while (running.get() && !socket.isClosed) {
                val read = try {
                    input.read(scratch)
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (_: EOFException) {
                    break
                } catch (_: Exception) {
                    break
                }
                if (read < 0) break
                var offset = 0
                while (offset < read) {
                    if (phase == PHASE_HEADER) {
                        val take = minOf(HEADER_BYTES - headerFill, read - offset)
                        System.arraycopy(scratch, offset, header, headerFill, take)
                        headerFill += take
                        offset += take
                        if (headerFill == HEADER_BYTES) {
                            val length = parseHeader(header)
                            if (length > 0) {
                                if (payload.size < length) payload = ByteArray(length)
                                payloadLength = length
                                payloadFill = 0
                                phase = PHASE_PAYLOAD
                            } else {
                                // Invalid header: drop only the oldest byte so a
                                // real header starting inside the window is found.
                                System.arraycopy(header, 1, header, 0, headerFill - 1)
                                headerFill -= 1
                            }
                        }
                    } else {
                        val take = minOf(payloadLength - payloadFill, read - offset)
                        System.arraycopy(scratch, offset, payload, payloadFill, take)
                        payloadFill += take
                        offset += take
                        if (payloadFill == payloadLength) {
                            enqueueDecode(payload, payloadLength)
                            phase = PHASE_HEADER
                            headerFill = 0
                        }
                    }
                }
            }
        }
    }

    /** Returns the payload length when [header] is a valid frame header, else 0. */
    private fun parseHeader(header: ByteArray): Int {
        for (i in HEADER.indices) {
            if (header[i] != HEADER[i]) return 0
        }
        val length = String(header, HEADER.size, 8, Charsets.US_ASCII).toIntOrNull(16) ?: return 0
        return if (length > 0 && length <= MAX_FRAME_BYTES) length else 0
    }

    /**
     * Hands the payload to the decoder thread. BitmapFactory on a 1080p JPEG
     * takes 10–20 ms on an old phone; decoding inline would stall the reader,
     * backpressure the PC and collapse the fps. Decode failures just skip a
     * frame — the stream re-synchronizes on the next header.
     */
    private fun enqueueDecode(payload: ByteArray, length: Int) {
        if (!decodeThreadRunning) {
            startDecodeThread()
        }
        val accepted = decodeQueue.offer(FrameTask(payload.copyOf(length)))
        if (!accepted) {
            // Queue full (decoder stalled): drop everything stale and keep
            // only the newest frame — latency beats completeness here.
            decodeQueue.clear()
            decodeQueue.offer(FrameTask(payload.copyOf(length)))
        }
    }

    private class FrameTask(val data: ByteArray)

    private val decodeQueue = java.util.concurrent.ArrayBlockingQueue<FrameTask>(4)
    @Volatile private var decodeThreadRunning = false
    private val decodeLock = Any()

    private fun startDecodeThread() {
        synchronized(decodeLock) {
            if (decodeThreadRunning) return
            decodeThreadRunning = true
        }
        Thread({
            while (true) {
                val task = try {
                    decodeQueue.take()
                } catch (_: InterruptedException) {
                    break
                }
                if (decodeFrame(task.data, task.data.size)) {
                    framesReceived.incrementAndGet()
                    frameSeq.incrementAndGet()
                }
            }
            synchronized(decodeLock) { decodeThreadRunning = false }
        }, "TabletPreviewDecoder").apply {
            isDaemon = true
            start()
        }
    }

    /** Decodes the JPEG payload and publishes it; returns false on a bad frame. */
    private fun decodeFrame(payload: ByteArray, length: Int): Boolean {
        return try {
            val bitmap = BitmapFactory.decodeByteArray(payload, 0, length)
                ?: return false
            frameWidth.set(bitmap.width)
            frameHeight.set(bitmap.height)
            latestFrame.set(bitmap)
            true
        } catch (_: Exception) {
            false
        }
    }
}
