package com.denis.spenfix

import android.util.Log
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference

/** Streams normalized tablet frames over TCP without blocking digitizer capture. */
object TabletNetworkServer {
    private const val TAG = "TabletNetworkServer"
    const val PORT = 7654

    private data class Frame(
        val x: Float, val y: Float, val pressure: Float,
        val touching: Boolean, val button: Boolean, val eraser: Boolean, val inRange: Boolean
    )

    @Volatile private var running = false
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var client: Socket? = null
    private val latestFrame = AtomicReference<Frame?>(null)
    private var serverThread: Thread? = null

    fun start(onStatusChange: (connected: Boolean) -> Unit) {
        if (running) return
        running = true
        serverThread = Thread({
            try {
                ServerSocket(PORT).also { serverSocket = it }.use { server ->
                    while (running) {
                        val socket = try { server.accept() } catch (_: Exception) { break }
                        if (!running) { socket.close(); break }
                        socket.tcpNoDelay = true
                        client = socket
                        onStatusChange(true)
                        streamClient(socket, onStatusChange)
                    }
                }
            } catch (error: Exception) {
                if (running) Log.w(TAG, "Server stopped: ${error.message}")
            } finally {
                running = false
                client = null
                onStatusChange(false)
            }
        }, "TabletNetworkServer").apply { isDaemon = true; start() }
    }

    private fun streamClient(socket: Socket, onStatusChange: (connected: Boolean) -> Unit) {
        try {
            // The writer owns the output stream; the server thread only waits for
            // disconnects. Frames are replaced atomically, never queued unboundedly.
            val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.US_ASCII))
            while (running && !socket.isClosed) {
                val frame = latestFrame.getAndSet(null)
                if (frame != null) {
                    val flags = (if (frame.touching) 1 else 0) or
                        (if (frame.button) 2 else 0) or
                        (if (frame.eraser) 4 else 0) or
                        (if (frame.inRange) 8 else 0)
                    writer.write("%.5f,%.5f,%.5f,%d\\n".format(
                        frame.x, frame.y, frame.pressure, flags
                    ))
                    writer.flush()
                } else {
                    try { Thread.sleep(4L) } catch (_: InterruptedException) { break }
                }
                // A read with a short timeout detects a disconnected client.
                socket.soTimeout = 20
                try { socket.getInputStream().read() } catch (_: java.net.SocketTimeoutException) { }
            }
        } catch (error: Exception) {
            if (running) Log.w(TAG, "Client disconnected: ${error.message}")
        } finally {
            try { socket.close() } catch (_: Exception) { }
            client = null
            onStatusChange(false)
        }
    }

    fun sendFrame(
        x: Float, y: Float, pressure: Float,
        touching: Boolean, button: Boolean, eraser: Boolean, inRange: Boolean
    ): Boolean {
        if (!running || client == null) return false
        latestFrame.set(Frame(x, y, pressure, touching, button, eraser, inRange))
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
}
