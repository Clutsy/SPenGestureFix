package com.denis.spenfix

/**
 * Trova il percorso /dev/input/eventX associato a un device di input
 * cercando il suo nome dentro /proc/bus/input/devices (letto come root).
 *
 * Serve perché il numero (event3, event7, event16...) può cambiare da
 * un riavvio all'altro o dopo un flash, mentre il nome del driver
 * (es. "sec_e-pen") resta stabile.
 */
object EventDeviceFinder {

    fun findDevicePath(deviceName: String): String? {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat /proc/bus/input/devices"))
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()

            val blocks = output.split(Regex("\\n\\s*\\n"))
            for (block in blocks) {
                if (block.contains("Name=\"$deviceName\"")) {
                    val handlerLine = block.lines().firstOrNull { it.trim().startsWith("H:") }
                    val match = handlerLine?.let { Regex("event(\\d+)").find(it) }
                    if (match != null) {
                        return "/dev/input/event${match.groupValues[1]}"
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }
}
