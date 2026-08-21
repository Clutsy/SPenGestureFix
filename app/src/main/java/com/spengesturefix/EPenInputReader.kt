package com.spengesturefix

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Streams one Linux input device without touching Android's MotionEvent pipeline.
 *
 * The process is intentionally owned by this reader. Do not use a global pkill:
 * the service can have two independent readers (w1 and sec_e-pen) alive at once.
 */
class EPenInputReader(
    private val devicePath: String,
    private val onEvent: (type: String, code: String, value: String) -> Unit
) {
    companion object {
        private const val TAG = "EPenInputReader"
        private val DEVICE_PATH = Regex("/dev/input/event\\d+")
        private val SPLIT = Regex("\\s+")

        /** Enable with `setprop log.tag.SPenDebug DEBUG` during device diagnostics. */
        private val DEBUG_LOG = try {
            Log.isLoggable("SPenDebug", Log.DEBUG)
        } catch (_: Throwable) {
            false
        }

        fun parseLine(line: String): Triple<String, String, String>? {
            val parts = line.trim().split(SPLIT)
            if (parts.isEmpty()) return null
            val offset = if (parts.first().startsWith("/dev/")) 1 else 0
            if (parts.size < offset + 3) return null

            val type = parts[offset].removeSuffix(":")
            var code = parts[offset + 1]
            var value = parts[offset + 2]
            // Some old toolbox builds print an unknown switch as:
            // `EV_SW SW 001a 00000001` instead of `EV_SW SW_001A 00000001`.
            if (type == "EV_SW" && code == "SW" && parts.size >= offset + 4) {
                code = parts[offset + 2]
                value = parts[offset + 3]
            }
            return Triple(type, code, value)
        }
    }

    private val running = AtomicBoolean(false)
    @Volatile private var process: Process? = null
    @Volatile private var childPid: Int? = null
    @Volatile private var worker: Thread? = null

    fun start() {
        if (!DEVICE_PATH.matches(devicePath)) {
            Log.e(TAG, "Refusing invalid input device path: $devicePath")
            return
        }
        if (!running.compareAndSet(false, true)) return

        worker = Thread(::readLoop, "SpenInput-${devicePath.substringAfterLast('/')}").apply {
            isDaemon = true
            start()
        }
    }

    private fun readLoop() {
        try {
            // Magisk `su` can daemonize the command it launches. Run a tiny
            // root shell that prints its own pid before exec'ing getevent, so
            // stop() can terminate exactly this reader instead of using pkill.
            val command = "echo ${'$'}${'$'}; exec getevent -l $devicePath"
            val started = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            process = started
            var pidLineRead = false

            BufferedReader(InputStreamReader(started.inputStream)).use { reader ->
                while (running.get()) {
                    val line = reader.readLine() ?: break
                    if (!pidLineRead) {
                        val parsedPid = line.trim().toIntOrNull()
                        if (parsedPid != null && parsedPid > 1) {
                            childPid = parsedPid
                            pidLineRead = true
                            Log.d(TAG, "Started $devicePath reader pid=$parsedPid")
                            continue
                        }
                        // Keep compatibility with su implementations that do
                        // not preserve the pid line and emit events immediately.
                        pidLineRead = true
                    }
                    val event = parseLine(line) ?: continue
                    val (type, code, value) = event
                    if (type == "EV_SYN") continue
                    if (DEBUG_LOG) Log.d("SPenDebug", "$devicePath -> $type $code $value")
                    try {
                        onEvent(type, code, value)
                    } catch (callbackError: Throwable) {
                        // A malformed callback must never terminate the reader.
                        Log.e(TAG, "Input callback failed for $devicePath", callbackError)
                    }
                }
            }
        } catch (error: Exception) {
            if (running.get()) Log.e(TAG, "Reading $devicePath failed", error)
        } finally {
            process = null
            childPid = null
            running.set(false)
        }
    }

    /** Stops only this reader and returns immediately. */
    fun stop() {
        if (!running.getAndSet(false)) return
        val pid = childPid
        try {
            process?.inputStream?.close()
        } catch (_: Exception) {
        }
        try {
            process?.destroy()
        } catch (_: Exception) {
        }
        if (pid != null && pid > 1) {
            // This is scoped to the pid printed by this reader; it cannot
            // terminate the other sec_e-pen/w1 pipeline.
            try {
                ProcessBuilder("su", "-c", "kill -TERM -$pid")
                    .redirectErrorStream(true)
                    .start()
            } catch (_: Exception) {
            }
        }
        process = null
        childPid = null
        worker = null
    }

    val isRunning: Boolean
        get() = running.get()
}
