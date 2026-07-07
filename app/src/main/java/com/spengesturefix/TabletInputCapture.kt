package com.denis.spenfix

import android.util.Log

data class TabletFrame(
    val x: Float,        // 0.0-1.0 normalized
    val y: Float,        // 0.0-1.0 normalized
    val pressure: Float, // 0.0-1.0 normalized (RAW)
    val touching: Boolean,
    val button: Boolean, // BTN_STYLUS
    val inRange: Boolean // BTN_DIGI / proximity
)

class TabletInputCapture(
    private val devicePath: String,
    private val onFrame: (TabletFrame) -> Unit
) {
    private var reader: EPenInputReader? = null

    private var rawX = 0
    private var rawY = 0
    private var rawPressure = 0
    private var touching = false
    private var button = false
    private var inRange = false

    fun start() {
        reader = EPenInputReader(devicePath) { type, code, value ->
            var changed = false
            try {
                when (type) {
                    "EV_ABS" -> {
                        val numVal = Integer.parseInt(value, 16)
                        when (code) {
                            "ABS_X" -> { rawX = numVal; changed = true }
                            "ABS_Y" -> { rawY = numVal; changed = true }
                            "ABS_PRESSURE" -> { rawPressure = numVal; changed = true }
                            "ABS_DISTANCE" -> {
                                // Fallback range check
                                val distInRange = numVal < 50
                                if (distInRange != inRange) {
                                    inRange = distInRange
                                    changed = true
                                }
                            }
                        }
                    }
                    "EV_KEY" -> {
                        val isDown = value == "DOWN" || value == "00000001"
                        when (code) {
                            "BTN_TOUCH" -> { touching = isDown; changed = true }
                            "BTN_STYLUS" -> { button = isDown; changed = true }
                            "BTN_DIGI", "BTN_TOOL_PEN" -> { inRange = isDown; changed = true }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("TabletInputCapture", "Error parsing event type=$type code=$code value=$value", e)
            }

            if (changed) {
                // Normalize. Note 3 typical values:
                // ABS_X: 0 to 4095
                // ABS_Y: 0 to 4095
                // ABS_PRESSURE: 0 to 1024
                val normX = rawX / 4095f
                val normY = rawY / 4095f
                val normPressure = rawPressure / 1024f
                onFrame(TabletFrame(
                    x = normX.coerceIn(0f, 1f),
                    y = normY.coerceIn(0f, 1f),
                    pressure = normPressure.coerceIn(0f, 1f),
                    touching = touching,
                    button = button,
                    inRange = inRange
                ))
            }
        }
        reader?.start()
    }

    fun stop() {
        reader?.stop()
        reader = null
    }
}
