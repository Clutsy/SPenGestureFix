package com.spengesturefix

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
        private val STYLUS_BUTTON_CODES = setOf("BTN_STYLUS", "BTN_STYLUS2")
        private val TOOL_CODES = setOf("BTN_DIGI", "BTN_TOOL_PEN", "BTN_TOOL_RUBBER")
    }

    private val scheduler = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "SpenGestureAnalyzer").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()

    private var pendingSingleClick: ScheduledFuture<*>? = null
    private var longPressFuture: ScheduledFuture<*>? = null
    private var awaitingSecondClick = false
    private var stylusButtonDown = false
    private var longPressFired = false
    private var penToolInRange = false
    private var rubberToolInRange = false
    private var touching = false
    private var isHovering = false

    fun onEvent(type: String, code: String, value: String) {
        if (type != "EV_KEY") return
        val action = keyAction(value) ?: return
        when {
            code in STYLUS_BUTTON_CODES -> handleStylusButton(action)
            code in TOOL_CODES -> {
                synchronized(lock) {
                    if (code == "BTN_TOOL_RUBBER") rubberToolInRange = action
                    else penToolInRange = action
                }
                updateHoverState()
            }
            code == "BTN_TOUCH" -> {
                synchronized(lock) { touching = action }
                updateHoverState()
            }
        }
    }

    private fun updateHoverState() {
        val hovering: Boolean
        synchronized(lock) {
            hovering = (penToolInRange || rubberToolInRange) && !touching
            if (hovering == isHovering) return
            isHovering = hovering
        }
        // A state change is rare; never run app callbacks on the reader thread.
        mainHandler.post { onHoverChanged(hovering) }
    }

    private fun handleStylusButton(down: Boolean) {
        if (down) {
            synchronized(lock) {
                // Linux may emit value 2 (repeat) while the button is held.
                // It is not a second press and must not restart the timer.
                if (stylusButtonDown) return
                stylusButtonDown = true
                longPressFired = false
                longPressFuture?.cancel(false)
                // A second press starts before the first-click timer expires on
                // some hardware. Cancel the pending single, but keep the
                // awaiting flag until the second release.
                if (awaitingSecondClick) {
                    pendingSingleClick?.cancel(false)
                    pendingSingleClick = null
                }
                longPressFuture = scheduler.schedule({
                    synchronized(lock) {
                        if (!stylusButtonDown) return@schedule
                        longPressFired = true
                        awaitingSecondClick = false
                        pendingSingleClick?.cancel(false)
                        pendingSingleClick = null
                    }
                    mainHandler.post(onLongPress)
                }, LONG_PRESS_THRESHOLD_MS, TimeUnit.MILLISECONDS)
            }
            return
        }

        synchronized(lock) {
            if (!stylusButtonDown) return
            stylusButtonDown = false
            longPressFuture?.cancel(false)
            longPressFuture = null
            if (longPressFired) {
                // The long-press callback has already been delivered.
                longPressFired = false
                return
            }

            if (awaitingSecondClick) {
                pendingSingleClick?.cancel(false)
                pendingSingleClick = null
                awaitingSecondClick = false
                mainHandler.post(onDoubleClick)
            } else {
                awaitingSecondClick = true
                pendingSingleClick = scheduler.schedule({
                    synchronized(lock) {
                        if (!awaitingSecondClick) return@schedule
                        awaitingSecondClick = false
                        pendingSingleClick = null
                    }
                    mainHandler.post(onSingleClick)
                }, DOUBLE_CLICK_WINDOW_MS, TimeUnit.MILLISECONDS)
            }
        }
    }

    private fun keyAction(value: String): Boolean? = when (value.trim().uppercase()) {
        "DOWN", "1", "00000001", "REPEAT", "2", "00000002" -> true
        "UP", "0", "00000000" -> false
        else -> null
    }

    /** Cancels a partially completed gesture without shutting down the reader. */
    fun cancelPendingGesture() {
        synchronized(lock) {
            stylusButtonDown = false
            awaitingSecondClick = false
            longPressFired = false
            pendingSingleClick?.cancel(false)
            longPressFuture?.cancel(false)
            pendingSingleClick = null
            longPressFuture = null
        }
    }

    fun close() {
        synchronized(lock) {
            stylusButtonDown = false
            pendingSingleClick?.cancel(false)
            longPressFuture?.cancel(false)
            pendingSingleClick = null
            longPressFuture = null
        }
        scheduler.shutdownNow()
    }
}
