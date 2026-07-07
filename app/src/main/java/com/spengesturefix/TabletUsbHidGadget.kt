package com.denis.spenfix

import android.content.Context
import android.util.Log
import java.io.File

object TabletUsbHidGadget {
    private const val TAG = "TabletUsbHidGadget"
    private var originalFunctions: String = "mtp,adb"

    val descriptorBytes = byteArrayOf(
        0x05, 0x0D,         // Usage Page (Digitizers)
        0x09, 0x02,         // Usage (Pen)
        0xA1.toByte(), 0x01,         // Collection (Application)
        0x09, 0x20,         //   Usage (Stylus)
        0xA1.toByte(), 0x02,         //   Collection (Logical)
        0x09, 0x42,         //     Usage (Tip Switch)
        0x09, 0x44,         //     Usage (Barrel Switch)
        0x09, 0x45,         //     Usage (Eraser)
        0x09, 0x3C,         //     Usage (In Range)
        0x15, 0x00,         //     Logical Minimum (0)
        0x25, 0x01,         //     Logical Maximum (1)
        0x75, 0x01,         //     Report Size (1)
        0x95.toByte(), 0x04,         //     Report Count (4)
        0x81.toByte(), 0x02,         //     Input (Data, Var, Abs)
        0x95.toByte(), 0x04,         //     Report Count (4)
        0x81.toByte(), 0x03,         //     Input (Cnst, Var, Abs) - padding (4 bits)
        0x05, 0x01,         //     Usage Page (Generic Desktop)
        0x09, 0x30,         //     Usage (X)
        0x09, 0x31,         //     Usage (Y)
        0x16, 0x00, 0x00,   //     Logical Minimum (0)
        0x26, 0xFF.toByte(), 0x7F,   //     Logical Maximum (32767)
        0x75, 0x10,         //     Report Size (16)
        0x95.toByte(), 0x02,         //     Report Count (2)
        0x81.toByte(), 0x02,         //     Input (Data, Var, Abs)
        0x05, 0x0D,         //     Usage Page (Digitizers)
        0x09, 0x30,         //     Usage (Tip Pressure)
        0x16, 0x00, 0x00,   //     Logical Minimum (0)
        0x26, 0x00, 0x04,   //     Logical Maximum (1024)
        0x75, 0x10,         //     Report Size (16)
        0x95.toByte(), 0x01,         //     Report Count (1)
        0x81.toByte(), 0x02,         //     Input (Data, Var, Abs)
        0xC0.toByte(),               //   End Collection
        0xC0.toByte()                // End Collection
    )

    fun isAvailable(): Boolean {
        // Check if /dev/hidg0 exists or if f_hid directory exists in sysfs
        return try {
            val devExists = File("/dev/hidg0").exists()
            if (devExists) return true
            
            // Check sysfs paths
            val paths = listOf(
                "/sys/class/android_usb/f_hid",
                "/sys/module/g_android/parameters"
            )
            paths.any { File(it).exists() }
        } catch (e: Exception) {
            false
        }
    }

    fun setup(context: Context): Boolean {
        try {
            // Write descriptor to cache file first
            val cacheFile = File(context.cacheDir, "spen_hid_desc")
            cacheFile.writeBytes(descriptorBytes)

            // Save original functions
            originalFunctions = runRootCommandWithOutput("cat /sys/class/android_usb/android0/functions").trim()
            if (originalFunctions.isBlank()) {
                originalFunctions = "mtp,adb"
            }

            Log.d(TAG, "Original USB functions: $originalFunctions")

            // Copy descriptor to /data/local/tmp
            runRootCommand("cp ${cacheFile.absolutePath} /data/local/tmp/spen_hid_desc")
            runRootCommand("chmod 644 /data/local/tmp/spen_hid_desc")

            // Disable USB controller
            runRootCommand("echo 0 > /sys/class/android_usb/android0/enable")

            // Determine target functions. Keep ADB if it was present
            val targetFunctions = if (originalFunctions.contains("adb")) {
                "hid,adb"
            } else {
                "hid"
            }
            runRootCommand("echo $targetFunctions > /sys/class/android_usb/android0/functions")

            // Set report size (7 bytes) and descriptor
            // Try different standard sysfs paths for legacy kernels
            val paths = listOf(
                "/sys/class/android_usb/f_hid/parameters",
                "/sys/module/g_android/parameters"
            )
            
            var pathConfigured = false
            for (p in paths) {
                if (File(p).exists()) {
                    runRootCommand("echo 7 > $p/report_length")
                    runRootCommand("cat /data/local/tmp/spen_hid_desc > $p/report_desc")
                    pathConfigured = true
                    break
                }
            }

            if (!pathConfigured) {
                // Try fallback layout direct to android0/f_hid
                runRootCommand("echo 7 > /sys/class/android_usb/android0/f_hid/report_length")
                runRootCommand("cat /data/local/tmp/spen_hid_desc > /sys/class/android_usb/android0/f_hid/report_desc")
            }

            // Set USB device parameters (look like generic digitizer)
            runRootCommand("echo 2FDE > /sys/class/android_usb/android0/idVendor")
            runRootCommand("echo 0001 > /sys/class/android_usb/android0/idProduct")

            // Enable USB controller
            runRootCommand("echo 1 > /sys/class/android_usb/android0/enable")

            // Wait a bit and check if /dev/hidg0 is ready
            Thread.sleep(800)
            val ready = File("/dev/hidg0").exists()
            if (ready) {
                runRootCommand("chmod 666 /dev/hidg0")
            }
            return ready
        } catch (e: Exception) {
            Log.e(TAG, "Error configuring USB HID Gadget", e)
            return false
        }
    }

    fun teardown() {
        try {
            Log.d(TAG, "Restoring USB functions to $originalFunctions")
            runRootCommand("echo 0 > /sys/class/android_usb/android0/enable")
            runRootCommand("echo $originalFunctions > /sys/class/android_usb/android0/functions")
            
            // Restore vendor/product IDs to defaults (Samsung Note 3 is usually 04E8)
            runRootCommand("echo 04E8 > /sys/class/android_usb/android0/idVendor")
            runRootCommand("echo 6860 > /sys/class/android_usb/android0/idProduct") // MTP + ADB Product ID

            runRootCommand("echo 1 > /sys/class/android_usb/android0/enable")
        } catch (e: Exception) {
            Log.e(TAG, "Error tearing down USB HID Gadget", e)
        }
    }

    private fun runRootCommand(cmd: String) {
        try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", cmd)).waitFor()
        } catch (_: Exception) {}
    }

    private fun runRootCommandWithOutput(cmd: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            output
        } catch (e: Exception) {
            ""
        }
    }
}
