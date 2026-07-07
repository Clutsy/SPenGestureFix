package com.denis.spenfix

import android.os.Handler
import android.os.Looper

/**
 * Interpreta la sequenza di eventi del digitizer "sec_e-pen" in gesti:
 * - clic singolo del tasto laterale (BTN_STYLUS)
 * - doppio clic (rilascio + rilascio entro doubleClickWindowMs)
 * - pressione lunga (tasto tenuto premuto oltre longPressThresholdMs)
 * - hover: BTN_DIGI a DOWN (penna rilevata in prossimità) e BTN_TOUCH
 *   a UP (non a contatto).
 *
 * NOTA sui dati reali raccolti il 06/07/2026 con
 * `su -c "getevent -l /dev/input/event3"` (hover puro, mai BTN_TOUCH):
 * ABS_DISTANCE oscillava solo tra 0x0d e 0x1a (13-26 circa), molto
 * lontano dal massimo dichiarato dal device (1024). Invece di indovinare
 * una soglia su ABS_DISTANCE, l'hover usa BTN_DIGI/BTN_TOUCH: è il
 * digitizer stesso a dire quando il pennino entra/esce dal raggio di
 * rilevamento, zero calibrazione necessaria.
 */
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

    private val handler = Handler(Looper.getMainLooper())
    private var pendingSingleClick: Runnable? = null
    private var awaitingSecondClick = false

    private var buttonDownAt = 0L
    private var longPressFired = false
    private val longPressRunnable = Runnable {
        longPressFired = true
        awaitingSecondClick = false
        pendingSingleClick?.let { handler.removeCallbacks(it) }
        onLongPress()
    }

    private var toolInRange = false // BTN_DIGI: penna rilevata (hover o contatto)
    private var touching = false    // BTN_TOUCH: penna effettivamente a contatto
    private var isHovering = false  // = toolInRange && !touching

    fun onEvent(type: String, code: String, value: String) {
        if (type != "EV_KEY") return
        when (code) {
            "BTN_STYLUS" -> handleStylusButton(value)
            "BTN_DIGI" -> { toolInRange = value == "DOWN"; updateHoverState() }
            "BTN_TOUCH" -> { touching = value == "DOWN"; updateHoverState() }
        }
    }

    private fun updateHoverState() {
        val hovering = toolInRange && !touching
        if (hovering != isHovering) {
            isHovering = hovering
            onHoverChanged(hovering)
        }
    }

    private fun handleStylusButton(value: String) {
        if (value == "DOWN") {
            buttonDownAt = System.currentTimeMillis()
            longPressFired = false
            handler.postDelayed(longPressRunnable, LONG_PRESS_THRESHOLD_MS)
            return
        }

        // value == "UP"
        handler.removeCallbacks(longPressRunnable)
        if (longPressFired) return // già gestito come pressione lunga, ignora il rilascio

        if (awaitingSecondClick) {
            pendingSingleClick?.let { handler.removeCallbacks(it) }
            awaitingSecondClick = false
            onDoubleClick()
        } else {
            awaitingSecondClick = true
            val runnable = Runnable {
                awaitingSecondClick = false
                onSingleClick()
            }
            pendingSingleClick = runnable
            handler.postDelayed(runnable, DOUBLE_CLICK_WINDOW_MS)
        }
    }
}
