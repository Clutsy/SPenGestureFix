package com.spengesturefix

import android.util.Log

/** Limits reported by the Linux input device for absolute axes. */
data class InputDeviceCapabilities(
    val xMin: Int = 0,
    val xMax: Int = 4095,
    val yMin: Int = 0,
    val yMax: Int = 4095,
    val pressureMin: Int = 0,
    val pressureMax: Int = 1024
)

/** Root-only discovery of stable input device names and their capabilities. */
object EventDeviceFinder {
    private const val TAG = "EventDeviceFinder"
    private val EVENT_PATH = Regex("/dev/input/event\\d+")
    private val EVENT_HANDLER = Regex("\\bevent\\d+\\b")
    // /proc/bus/input/devices uses: N: Name="sec_e-pen"
    private val NAME = Regex("Name=\"([^\"]+)\"")
    private val GETEVENT_NAME = Regex("^\\s*name:\\s*\"([^\"]+)\"\\s*$")
    private val AXIS = Regex("(ABS_[A-Z0-9_]+).*?min\\s+(-?\\d+),\\s*max\\s+(-?\\d+)")

    fun findDevicePath(deviceName: String): String? {
        if (!isSafeName(deviceName)) return null

        // Some ROMs deny /proc/bus/input/devices even to the Magisk shell
        // context. Keep it as the first, cheap path for ROMs that
        // expose it, then use getevent -lp, which is already required to read
        // the input devices and is available on the target device.
        val procOutput = runRoot("cat /proc/bus/input/devices")
        return procOutput?.let { findDevicePathFromInput(it, deviceName) }
            ?: runRoot("getevent -lp")?.let { findDevicePathFromGetevent(it, deviceName) }
    }

    /** Pure parser for /proc/bus/input/devices; useful for device-specific regression tests. */
    fun findDevicePathFromInput(output: String, deviceName: String): String? {
        val blocks = output.split(Regex("\\r?\\n\\s*\\r?\\n"))
        return blocks.firstNotNullOfOrNull { block ->
            val name = NAME.find(block)?.groupValues?.getOrNull(1) ?: return@firstNotNullOfOrNull null
            if (name != deviceName && !name.contains(deviceName, ignoreCase = true)) {
                return@firstNotNullOfOrNull null
            }
            EVENT_PATH.find(block)?.value
                ?: EVENT_HANDLER.find(block)?.value?.let { "/dev/input/$it" }
        }
    }

    /**
     * Pure parser for the `getevent -lp` format emitted by Android toolbox/toybox.
     * Example:
     *
     * add device 16: /dev/input/event3
     *   name:     "sec_e-pen"
     */
    fun findDevicePathFromGetevent(output: String, deviceName: String): String? {
        var currentPath: String? = null
        var currentName: String? = null

        fun matchesCurrent(): Boolean {
            val name = currentName ?: return false
            return name == deviceName || name.contains(deviceName, ignoreCase = true)
        }

        // A `for` loop lets us finish a block as soon as the next device starts;
        // this avoids relying on blank lines, which older toolbox versions omit.
        for (rawLine in output.lineSequence()) {
            val line = rawLine.trimEnd('\r')
            if (line.startsWith("add device ")) {
                if (currentPath != null && matchesCurrent()) return currentPath
                currentPath = EVENT_PATH.find(line)?.value
                currentName = null
                continue
            }

            GETEVENT_NAME.find(line)?.groupValues?.getOrNull(1)?.let { currentName = it }
        }

        return currentPath?.takeIf { matchesCurrent() }
    }

    fun readSwitchState(switchName: String): String? {
        if (!isSafeName(switchName)) return null
        val paths = buildList {
            add("/sys/class/switch/$switchName/state")
            add("/sys/devices/virtual/switch/$switchName/state")
            // Some w1 drivers expose the live slot state through the sec_e-pen
            // sysfs node instead of /sys/class/switch. Its values
            // are OK (inserted) and NG (removed) on the target ROM.
            if (switchName == "w1") add("/sys/class/sec/sec_epen/epen_connection")
        }
        return paths.firstNotNullOfOrNull { path ->
            runRoot("cat $path")?.trim()?.takeIf {
                it == "0" || it == "1" || it.equals("OK", true) || it.equals("NG", true)
            }
        }
    }

    fun readCapabilities(devicePath: String): InputDeviceCapabilities {
        if (!EVENT_PATH.matches(devicePath)) return InputDeviceCapabilities()
        val output = runRoot("getevent -lp $devicePath") ?: return InputDeviceCapabilities()
        var xMin = 0
        var xMax = 4095
        var yMin = 0
        var yMax = 4095
        var pressureMin = 0
        var pressureMax = 1024

        output.lineSequence().forEach { line ->
            val match = AXIS.find(line) ?: return@forEach
            val min = match.groupValues[2].toIntOrNull() ?: return@forEach
            val max = match.groupValues[3].toIntOrNull() ?: return@forEach
            when (match.groupValues[1]) {
                "ABS_X" -> { xMin = min; xMax = max }
                "ABS_Y" -> { yMin = min; yMax = max }
                "ABS_PRESSURE" -> { pressureMin = min; pressureMax = max }
            }
        }
        return InputDeviceCapabilities(xMin, xMax, yMin, yMax, pressureMin, pressureMax)
    }

    private fun isSafeName(value: String): Boolean =
        value.isNotBlank() && value.matches(Regex("[A-Za-z0-9_. -]+"))

    private fun runRoot(command: String): String? {
        return try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exit = process.waitFor()
            if (exit == 0) output else null
        } catch (error: Exception) {
            Log.w(TAG, "Root discovery failed: $command", error)
            null
        }
    }
}
