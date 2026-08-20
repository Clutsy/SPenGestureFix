package com.denis.spenfix

import android.os.Handler
import android.os.Looper
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Converts raw sec_e-pen key events into side-button gestures and hover state. */
class PenGestureAnalyzer(
    private val onSingleClick: () -> Unit,
    private val onDoubleClick: () -> Unit,
    private val onLongPress: () -> Unit,
    private val onHoverChanged: (Boolean) -> Unit
) {
    companion object {
        private const val DOUBLE_CLICK_WINDOW_MS = 350L
        private const val LONG_PRESS_THRESHOLD_MS = 500L
    }

    private val scheduler = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "SpenGestureAnalyzer").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()

    private var pendingSingleClick: ScheduledFuture<*>? = null
    private var longPressFuture: ScheduledFuture<*>? = null
    private var awaitingSecondClick = false
    private var longPressFired = false
    private var toolInRange = false
    private var touching = false
    private var isHovering = false

    fun onEvent(type: String, code: String, value: String) {
        if (type != "EV_KEY") return
        val down = value.equals("DOWN", true) || value == "00000001" || value == "1"
        when (code) {
            "BTN_STYLUS" -> handleStylusButton(down)
            "BTN_DIGI", "BTN_TOOL_PEN" -> {
                synchronized(lock) { toolInRange = down }
                updateHoverState()
            }
            "BTN_TOUCH" -> {
                synchronized(lock) { touching = down }
                updateHoverState()
            }
        }
    }

    private fun updateHoverState() {
        val hovering: Boolean
        synchronized(lock) {
            hovering = toolInRange && !touching
            if (hovering == isHovering) return
            isHovering = hovering
        }
        // A state change is rare; do not run app callbacks on the reader thread.
        mainHandler.post { onHoverChanged(hovering) }
    }

    private fun handleStylusButton(down: Boolean) {
        if (down) {
            synchronized(lock) {
                longPressFired = false
                longPressFuture?.cancel(false)
                longPressFuture = scheduler.schedule({
                    synchronized(lock) {
                        longPressFired = true
                        awaitingSecondClick = false
                        pendingSingleClick?.cancel(false)
                    }
                    mainHandler.post(onLongPress)
                }, LONG_PRESS_THRESHOLD_MS, TimeUnit.MILLISECONDS)
            }
            return
        }

        val shouldIgnoreRelease: Boolean
        synchronized(lock) {
            longPressFuture?.cancel(false)
            shouldIgnoreRelease = longPressFired
        }
        if (shouldIgnoreRelease) return

        synchronized(lock) {
            if (awaitingSecondClick) {
                pendingSingleClick?.cancel(false)
                awaitingSecondClick = false
                mainHandler.post(onDoubleClick)
            } else {
                awaitingSecondClick = true
                pendingSingleClick?.cancel(false)
                pendingSingleClick = scheduler.schedule({
                    synchronized(lock) { awaitingSecondClick = false }
                    mainHandler.post(onSingleClick)
                }, DOUBLE_CLICK_WINDOW_MS, TimeUnit.MILLISECONDS)
            }
        }
    }

    fun close() {
        synchronized(lock) {
            pendingSingleClick?.cancel(false)
            longPressFuture?.cancel(false)
            pendingSingleClick = null
            longPressFuture = null
        }
        scheduler.shutdownNow()
    }
}
