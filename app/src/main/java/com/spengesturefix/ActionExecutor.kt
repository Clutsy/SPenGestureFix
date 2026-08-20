package com.denis.spenfix

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager

/**
 * Single point where each ActionType becomes a real effect.
 * To add a new function: add an entry to ActionType and the
 * corresponding branch here — everything else (persistence, picker UI,
 * gesture binding, wheel slots) already works automatically.
 */
object ActionExecutor {

    private var flashlightOn = false
    private var wifiAssumedOn = true
    private var bluetoothAssumedOn = true

    fun execute(context: Context, action: PenAction, wheelOverlay: WheelOverlay) {
        when (action.type) {
            ActionType.NONE -> {}
            ActionType.LAUNCH_APP -> launchApp(context, action.target)
            ActionType.OPEN_WHEEL -> wheelOverlay.toggle()
            ActionType.SCREENSHOT -> takeScreenshot()
            ActionType.SCREEN_WRITE -> screenWrite(context)
            ActionType.SMART_SELECT -> smartSelect(context)
            ActionType.QUICK_NOTE -> openQuickNote(context)
            ActionType.APP_SEARCH -> openAppSearch(context)
            ActionType.PEN_WINDOW -> openPenWindow(context, action.target)
            ActionType.TOGGLE_FLASHLIGHT -> toggleFlashlight(context)
            ActionType.TOGGLE_WIFI -> toggleWifi()
            ActionType.TOGGLE_BLUETOOTH -> toggleBluetooth()
            ActionType.TOGGLE_MUTE -> rootShell("input keyevent 164") // KEYCODE_VOLUME_MUTE
            ActionType.LOCK_SCREEN -> rootShell("input keyevent 26") // KEYCODE_POWER
            ActionType.CUSTOM_SHELL -> rootShell(action.target)
        }
    }

    private fun launchApp(context: Context, packageName: String) {
        if (packageName.isBlank()) return
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent?.let { context.startActivity(it) }
        } catch (_: Exception) {
        }
    }

    private fun takeScreenshot() {
        Thread {
            rootShellSync(
                "mkdir -p /sdcard/Pictures/SPenScreenshots && " +
                    "screencap -p /sdcard/Pictures/SPenScreenshots/spen_${System.currentTimeMillis()}.png"
            )
        }.start()
    }

    private fun screenWrite(context: Context) {
        Thread {
            val path = "/sdcard/Pictures/SPenScreenshots/tmp_write_${System.currentTimeMillis()}.png"
            rootShellSync("mkdir -p /sdcard/Pictures/SPenScreenshots && screencap -p $path")
            val intent = Intent(context, ScreenWriteActivity::class.java).apply {
                putExtra(ScreenWriteActivity.EXTRA_IMAGE_PATH, path)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }.start()
    }

    private fun smartSelect(context: Context) {
        Thread {
            val path = "/sdcard/Pictures/SPenScreenshots/tmp_select_${System.currentTimeMillis()}.png"
            rootShellSync("mkdir -p /sdcard/Pictures/SPenScreenshots && screencap -p $path")
            val intent = Intent(context, SmartSelectActivity::class.java).apply {
                putExtra(SmartSelectActivity.EXTRA_IMAGE_PATH, path)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }.start()
    }

    private fun openQuickNote(context: Context) {
        context.startActivity(Intent(context, QuickNoteActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun openAppSearch(context: Context) {
        context.startActivity(Intent(context, AppSearchActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun openPenWindow(context: Context, packageName: String) {
        if (packageName.isBlank()) return
        Thread {
            try {
                // One-time setup (requires a reboot the first time for the
                // flag to be fully applied on some ROMs).
                rootShellSync("settings put global enable_freeform_support 1")
                rootShellSync("settings put global force_resizable_activities 1")

                val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return@Thread
                val component = launchIntent.component ?: return@Thread
                rootShellSync("am start --windowingMode 5 -n ${component.flattenToShortString()}")
            } catch (_: Exception) {
            }
        }.start()
    }

    private fun toggleFlashlight(context: Context) {
        try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull { camId ->
                cm.getCameraCharacteristics(camId).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return
            flashlightOn = !flashlightOn
            cm.setTorchMode(id, flashlightOn)
        } catch (_: Exception) {
        }
    }

    // WiFi/Bluetooth: instead of reading the system state (which would require
    // extra permissions on recent Android versions), we track the last state we
    // set ourselves. If you change it from outside the app, the toggle may end
    // up "out of sync" until you use it once more.
    private fun toggleWifi() {
        wifiAssumedOn = !wifiAssumedOn
        rootShell(if (wifiAssumedOn) "svc wifi enable" else "svc wifi disable")
    }

    private fun toggleBluetooth() {
        bluetoothAssumedOn = !bluetoothAssumedOn
        rootShell(if (bluetoothAssumedOn) "svc bluetooth enable" else "svc bluetooth disable")
    }

    private fun rootShell(command: String) {
        Thread { rootShellSync(command) }.start()
    }

    private fun rootShellSync(command: String) {
        try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", command)).waitFor()
        } catch (_: Exception) {
        }
    }
}
