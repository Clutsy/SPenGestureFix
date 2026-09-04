package com.spengesturefix

import android.util.Log
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/** Streams normalized tablet frames over TCP without blocking digitizer capture. */
object TabletNetworkServer {
    private const val TAG = "TabletNetworkServer"
    const val PORT = 7654

    /** Protocol flags shared with scripts/spen_mouse_emulator.py. */
    const val FLAG_TOUCH = 1
    const val FLAG_RIGHT_BUTTON = 2
    const val FLAG_ERASER = 4
    const val FLAG_IN_RANGE = 8
    const val FLAG_MIDDLE_BUTTON = 16

    private data class Frame(
        val x: Float,
        val y: Float,
        val pressure: Float,
        val touching: Boolean,
        val button: Boolean,
        val eraser: Boolean,
        val middleButton: Boolean,
        val inRange: Boolean
    )

    private data class StreamMetadata(
        val sourceWidth: Int,
        val sourceHeight: Int,
        val sourceRotation: Int,
        val orientation: String
    )

    @Volatile private var running = false
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var client: Socket? = null
    @Volatile private var frameIntervalNanos = 1_000_000_000L / 133L
    @Volatile private var metadata = StreamMetadata(0, 0, 0, "landscape")
    private val latestFrame = AtomicReference<Frame?>(null)
    @Volatile private var framesSent = 0L
    @Volatile private var bytesSent = 0L
    private var serverThread: Thread? = null

    val isClientConnected: Boolean
        get() = client != null

    /** Total frames flushed to the PC during this server lifetime. */
    fun framesSent(): Long = framesSent

    /** Approximate bytes written to the PC during this server lifetime. */
    fun bytesSent(): Long = bytesSent

    fun start(
        onStatusChange: (connected: Boolean) -> Unit,
        reportRateHz: Int = 133,
        sourceWidth: Int = 0,
        sourceHeight: Int = 0,
        sourceRotation: Int = 0,
        orientation: String = "landscape"
    ) {
        if (running) return
        running = true
        val safeRate = reportRateHz.coerceIn(30, 200)
        frameIntervalNanos = 1_000_000_000L / safeRate.toLong()
        metadata = StreamMetadata(
            sourceWidth.coerceAtLeast(0),
            sourceHeight.coerceAtLeast(0),
            sourceRotation.coerceIn(0, 3),
            orientation.ifBlank { "landscape" }
        )
        latestFrame.set(null)
        framesSent = 0L
        bytesSent = 0L
        serverThread = Thread({
            try {
                ServerSocket(PORT).also { serverSocket = it }.use { server ->
                    while (running) {
                        val socket = try {
                            server.accept()
                        } catch (_: Exception) {
                            break
                        }
                        if (!running) {
                            socket.close()
                            break
                        }
                        socket.tcpNoDelay = true
                        // Latest connection wins: the PC reconnects after a
                        // Wi-Fi hiccup, so a zombie socket from the previous
                        // connection must be evicted, never queued ahead of
                        // the new client.
                        client?.let { previous ->
                            try { previous.close() } catch (_: Exception) { }
                            Log.i(TAG, "Evicted previous tablet client for a new one")
                        }
                        client = socket
                        notifyStatus(onStatusChange, true)
                        streamClient(socket, onStatusChange)
                    }
                }
            } catch (error: Exception) {
                if (running) Log.w(TAG, "Server stopped: ${error.message}")
            } finally {
                running = false
                client = null
                notifyStatus(onStatusChange, false)
            }
        }, "TabletNetworkServer").apply {
            isDaemon = true
            start()
        }
    }

    private fun streamClient(socket: Socket, onStatusChange: (connected: Boolean) -> Unit) {
        try {
            // The app only sends frames. A write failure is sufficient to
            // detect a disconnected peer; polling the input stream every few
            // milliseconds only burns CPU on older phones.
            val writer = BufferedWriter(
                OutputStreamWriter(socket.getOutputStream(), Charsets.US_ASCII)
            )
            writer.write(metadataLine(metadata))
            writer.flush()

            var lastHeartbeat = 0L
            while (running && !socket.isClosed) {
                val tick = System.nanoTime()
                // One-byte heartbeat once per second while the pen is idle.
                // Without it a static hand sends no traffic at all, the
                // phone's Wi-Fi radio drops into power save, and the next
                // pen-down stutters or resets the link. The PC clients ignore
                // lines that are not complete frames.
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastHeartbeat >= 1000L) {
                    try {
                        writer.write("#hb\n")
                        writer.flush()
                        lastHeartbeat = nowMs
                    } catch (_: Exception) {
                        break
                    }
                }
                latestFrame.getAndSet(null)?.let { frame ->
                    var flags = 0
                    if (frame.touching) flags = flags or FLAG_TOUCH
                    if (frame.button) flags = flags or FLAG_RIGHT_BUTTON
                    if (frame.eraser) flags = flags or FLAG_ERASER
                    if (frame.inRange) flags = flags or FLAG_IN_RANGE
                    if (frame.middleButton) flags = flags or FLAG_MIDDLE_BUTTON

                    // Keep this as an actual line feed: the PC client consumes
                    // one complete frame per line. A legacy client ignores the
                    // preceding metadata line as an invalid frame.
                    val line = String.format(
                        Locale.US,
                        "%.5f,%.5f,%.5f,%d\n",
                        frame.x,
                        frame.y,
                        frame.pressure,
                        flags
                    )
                    writer.write(line)
                    writer.flush()
                    framesSent += 1L
                    bytesSent += line.length.toLong()
                }

                val remaining = frameIntervalNanos - (System.nanoTime() - tick)
                if (remaining > 0L) {
                    try {
                        val millis = remaining / 1_000_000L
                        val nanos = (remaining % 1_000_000L).toInt()
                        Thread.sleep(millis, nanos)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        } catch (error: Exception) {
            if (running) Log.w(TAG, "Client disconnected: ${error.message}")
        } finally {
            try { socket.close() } catch (_: Exception) { }
            if (client === socket) client = null
            notifyStatus(onStatusChange, false)
        }
    }

    /** Human-readable, comment-prefixed metadata that old clients can ignore. */
    fun metadataLine(
        sourceWidth: Int,
        sourceHeight: Int,
        sourceRotation: Int,
        orientation: String
    ): String = "#SPEN_TABLET 1 $sourceWidth $sourceHeight $sourceRotation ${orientation.ifBlank { "landscape" }}\n"

    private fun metadataLine(value: StreamMetadata): String =
        metadataLine(value.sourceWidth, value.sourceHeight, value.sourceRotation, value.orientation)

    fun sendFrame(
        x: Float,
        y: Float,
        pressure: Float,
        touching: Boolean,
        button: Boolean,
        eraser: Boolean,
        inRange: Boolean,
        middleButton: Boolean = false
    ): Boolean {
        if (!running || client == null) return false
        latestFrame.set(
            Frame(
                x.coerceIn(0f, 1f),
                y.coerceIn(0f, 1f),
                pressure.coerceIn(0f, 1f),
                touching,
                button,
                eraser,
                middleButton,
                inRange
            )
        )
        return true
    }

    fun stop() {
        running = false
        latestFrame.set(null)
        try { client?.close() } catch (_: Exception) { }
        try { serverSocket?.close() } catch (_: Exception) { }
        client = null
        serverSocket = null
        serverThread?.interrupt()
        serverThread = null
    }

    private fun notifyStatus(callback: (Boolean) -> Unit, connected: Boolean) {
        try {
            callback(connected)
        } catch (error: Exception) {
            Log.w(TAG, "Status callback failed", error)
        }
    }
}
