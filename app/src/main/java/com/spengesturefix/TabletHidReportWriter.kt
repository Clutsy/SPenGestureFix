package com.denis.spenfix

import android.util.Log
import java.io.FileOutputStream
import java.io.File

class TabletHidReportWriter {
    private var outputStream: FileOutputStream? = null
    private val reportBuffer = ByteArray(7)

    fun open(): Boolean {
        try {
            val file = File("/dev/hidg0")
            if (!file.exists()) {
                Log.e("TabletHidReportWriter", "/dev/hidg0 does not exist")
                return false
            }

            // Ensure permissions
            Runtime.getRuntime().exec(arrayOf("su", "-c", "chmod 666 /dev/hidg0")).waitFor()

            outputStream = FileOutputStream(file)
            return true
        } catch (e: Exception) {
            Log.e("TabletHidReportWriter", "Failed to open /dev/hidg0", e)
            return false
        }
    }

    fun writeReport(
        x: Float,
        y: Float,
        pressure: Float,
        touching: Boolean,
        button: Boolean,
        eraser: Boolean,
        inRange: Boolean
    ): Boolean {
        val stream = outputStream ?: return false
        try {
            // Build Byte 0 (Buttons and Flags)
            var b0 = 0
            if (touching) b0 = b0 or 0x01
            if (button)   b0 = b0 or 0x02
            if (eraser)   b0 = b0 or 0x04
            if (inRange)  b0 = b0 or 0x08
            reportBuffer[0] = b0.toByte()

            // Scale X to 0-32767 (16-bit uint)
            val xVal = (x * 32767f).toInt().coerceIn(0, 32767)
            reportBuffer[1] = (xVal and 0xFF).toByte()
            reportBuffer[2] = ((xVal shr 8) and 0xFF).toByte()

            // Scale Y to 0-32767 (16-bit uint)
            val yVal = (y * 32767f).toInt().coerceIn(0, 32767)
            reportBuffer[3] = (yVal and 0xFF).toByte()
            reportBuffer[4] = ((yVal shr 8) and 0xFF).toByte()

            // Scale Pressure to 0-1024 (16-bit uint)
            val pVal = (pressure * 1024f).toInt().coerceIn(0, 1024)
            reportBuffer[5] = (pVal and 0xFF).toByte()
            reportBuffer[6] = ((pVal shr 8) and 0xFF).toByte()

            stream.write(reportBuffer)
            stream.flush()
            return true
        } catch (e: Exception) {
            Log.e("TabletHidReportWriter", "Error writing HID report", e)
            return false
        }
    }

    fun close() {
        try {
            outputStream?.close()
        } catch (_: Exception) {}
        outputStream = null
    }
}
