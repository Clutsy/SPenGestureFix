package com.spengesturefix

import android.util.Log

/** A normalized frame emitted from the sec_e-pen input reader. */
data class TabletFrame(
    val x: Float,
    val y: Float,
    val pressure: Float,
    val touching: Boolean,
    val button: Boolean,
    val inRange: Boolean
)

class TabletInputCapture(
    private val devicePath: String,
    private val capabilities: InputDeviceCapabilities = InputDeviceCapabilities(),
    private val onFrame: (TabletFrame) -> Unit
) {
    private var reader: EPenInputReader? = null
    @Volatile private var rawX = capabilities.xMin
    @Volatile private var rawY = capabilities.yMin
    @Volatile private var rawPressure = capabilities.pressureMin
    @Volatile private var touching = false
    @Volatile private var button = false
    @Volatile private var inRange = false

    fun start() {
        if (reader != null) return
        reader = EPenInputReader(devicePath) { type, code, value ->
            var changed = false
            try {
                when (type) {
                    "EV_ABS" -> {
                        val number = parseValue(value)
                        if (number != null) {
                            when (code) {
                                "ABS_X" -> { rawX = number; changed = true }
                                "ABS_Y" -> { rawY = number; changed = true }
                                "ABS_PRESSURE" -> { rawPressure = number; changed = true }
                            }
                        }
                    }
                    "EV_KEY" -> {
                        val isDown = isDown(value)
                        when (code) {
                            "BTN_TOUCH" -> { touching = isDown; changed = true }
                            "BTN_STYLUS", "BTN_STYLUS2" -> { button = isDown; changed = true }
                            "BTN_DIGI", "BTN_TOOL_PEN", "BTN_TOOL_RUBBER" -> { inRange = isDown; changed = true }
                        }
                    }
                }
            } catch (error: Exception) {
                Log.w("TabletInputCapture", "Invalid input event $type/$code/$value", error)
            }
            if (changed) onFrame(snapshot())
        }
        reader?.start()
    }

    private fun snapshot(): TabletFrame = TabletFrame(
        x = normalize(rawX, capabilities.xMin, capabilities.xMax),
        y = normalize(rawY, capabilities.yMin, capabilities.yMax),
        pressure = normalize(rawPressure, capabilities.pressureMin, capabilities.pressureMax),
        touching = touching,
        button = button,
        inRange = inRange
    )

    fun stop() {
        reader?.stop()
        reader = null
    }

    /** Background-only variant used when handing the digitizer back to the service. */
    fun stopAndWait(timeoutMs: Long = 750L) {
        reader?.stopAndWait(timeoutMs)
        reader = null
    }

    companion object {
        fun normalize(value: Int, min: Int, max: Int): Float {
            if (max <= min) return 0f
            return ((value - min).toFloat() / (max - min).toFloat()).coerceIn(0f, 1f)
        }

        fun parseValue(value: String): Int? = try {
            when {
                value.equals("DOWN", true) -> 1
                value.equals("UP", true) -> 0
                value.startsWith("0x", true) -> value.substring(2).toLong(16).toInt()
                value.matches(Regex("[0-9a-fA-F]+")) && value.length > 2 -> value.toLong(16).toInt()
                else -> value.toInt()
            }
        } catch (_: NumberFormatException) {
            null
        }

        fun isDown(value: String): Boolean =
            value.equals("DOWN", true) || value.equals("1", true) ||
                value.equals("2", true) || value.equals("00000001", true) ||
                value.equals("00000002", true)
    }
}
