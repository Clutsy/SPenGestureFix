package com.spengesturefix

import android.util.Log
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/** Streams normalized tablet frames over TCP without blocking digitizer capture. */
object TabletNetworkServer {
    private const val TAG = "TabletNetworkServer"
    const val PORT = 7654

    private data class Frame(
        val x: Float,
        val y: Float,
        val pressure: Float,
        val touching: Boolean,
        val button: Boolean,
        val eraser: Boolean,
        val inRange: Boolean
    )

    @Volatile private var running = false
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var client: Socket? = null
    @Volatile private var frameIntervalNanos = 1_000_000_000L / 133L
    private val latestFrame = AtomicReference<Frame?>(null)
    private var serverThread: Thread? = null

    fun start(onStatusChange: (connected: Boolean) -> Unit, reportRateHz: Int = 133) {
        if (running) return
        running = true
        val safeRate = reportRateHz.coerceIn(30, 200)
        frameIntervalNanos = 1_000_000_000L / safeRate.toLong()
        latestFrame.set(null)
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
            // The app only sends frames. A short read timeout lets us notice a
            // peer that closed the connection while still bounding this loop.
            socket.soTimeout = 2
            val writer = BufferedWriter(
                OutputStreamWriter(socket.getOutputStream(), Charsets.US_ASCII)
            )
            val input = socket.getInputStream()
            while (running && !socket.isClosed) {
                val tick = System.nanoTime()
                latestFrame.getAndSet(null)?.let { frame ->
                    val flags = (if (frame.touching) 1 else 0) or
                        (if (frame.button) 2 else 0) or
                        (if (frame.eraser) 4 else 0) or
                        (if (frame.inRange) 8 else 0)
                    // Keep this as an actual line feed: the PC client consumes
                    // one complete frame per line.
                    writer.write(String.format(
                        Locale.US,
                        "%.5f,%.5f,%.5f,%d\n",
                        frame.x,
                        frame.y,
                        frame.pressure,
                        flags
                    ))
                    writer.flush()
                }

                try {
                    if (input.read() < 0) break
                } catch (_: SocketTimeoutException) {
                    // No inbound protocol is required; this is only a liveness poll.
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

    fun sendFrame(
        x: Float,
        y: Float,
        pressure: Float,
        touching: Boolean,
        button: Boolean,
        eraser: Boolean,
        inRange: Boolean
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

    val isClientConnected: Boolean
        get() = client != null

    private fun notifyStatus(callback: (Boolean) -> Unit, connected: Boolean) {
        try {
            callback(connected)
        } catch (error: Exception) {
            Log.w(TAG, "Status callback failed", error)
        }
    }
}
