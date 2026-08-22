package com.spengesturefix

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
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

    private data class StopState(
        val pid: Int?,
        val process: Process?,
        val worker: Thread?
    )

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
            if (!running.get()) {
                started.destroy()
                return
            }
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
            worker = null
            running.set(false)
        }
    }

    /**
     * Signals this reader to stop and returns immediately. This is safe from
     * Activity and Service lifecycle callbacks on the main thread.
     */
    fun stop() {
        stopInternal(waitForTermination = false, joinMs = 0L)
    }

    /**
     * Stops this reader and waits briefly for the owned process/thread. Use
     * only from a background handoff thread, never from UI input callbacks.
     */
    fun stopAndWait(timeoutMs: Long = 500L) {
        stopInternal(waitForTermination = true, joinMs = timeoutMs.coerceAtLeast(0L))
    }

    private fun stopInternal(waitForTermination: Boolean, joinMs: Long): StopState? {
        if (!running.getAndSet(false)) return null
        val state = StopState(childPid, process, worker)

        try {
            state.process?.inputStream?.close()
        } catch (_: Exception) {
        }
        try {
            state.process?.destroy()
        } catch (_: Exception) {
        }
        process = null
        childPid = null

        val terminator = state.pid?.takeIf { it > 1 }?.let(::launchPidTermination)
        if (waitForTermination) {
            try {
                // Android 5-7 do not expose Process.waitFor(timeout), so use
                // a small polling loop to keep even a broken su implementation
                // from holding the handoff worker forever.
                awaitProcess(terminator, 750L)
            } finally {
                try { terminator?.destroy() } catch (_: Exception) { }
            }
            joinWorker(state.worker, joinMs)
        } else if (terminator != null) {
            // Keep all potentially blocking process waits away from the main
            // looper while still reaping the exact-PID helper process.
            Thread {
                awaitProcess(terminator, 750L)
                try { terminator.destroy() } catch (_: Exception) { }
            }.apply {
                name = "SpenInputStop-${devicePath.substringAfterLast('/')}"
                isDaemon = true
                start()
            }
        }
        return state
    }

    private fun launchPidTermination(pid: Int): Process? {
        if (pid <= 1) return null
        return try {
            ProcessBuilder(
                "su",
                "-c",
                "kill -TERM $pid 2>/dev/null; kill -KILL $pid 2>/dev/null"
            ).redirectErrorStream(true).start()
        } catch (_: Exception) {
            null
        }
    }

    private fun awaitProcess(process: Process?, timeoutMs: Long) {
        if (process == null) return
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(0L))
        while (true) {
            try {
                process.exitValue()
                return
            } catch (_: IllegalThreadStateException) {
                if (System.nanoTime() >= deadline) {
                    try { process.destroy() } catch (_: Exception) { }
                    return
                }
                try {
                    Thread.sleep(10L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            } catch (_: Exception) {
                return
            }
        }
    }

    private fun joinWorker(thread: Thread?, timeoutMs: Long) {
        if (thread == null || thread === Thread.currentThread()) return
        try {
            thread.join(timeoutMs)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    val isRunning: Boolean
        get() = running.get()
}
