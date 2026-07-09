package com.denis.spenfix

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class SPenGestureService : Service() {

    companion object {
        const val CHANNEL_ID = "spen_gesture_service"
        const val NOTIFICATION_ID = 1
        const val DIGITIZER_DEVICE_NAME = "sec_e-pen"
        const val PRESENCE_DEVICE_NAME = "w1"
        const val PRESENCE_SWITCH_CODE = "001a"
    }

    private var digitizerReader: EPenInputReader? = null
    private var presenceReader: EPenInputReader? = null
    private lateinit var gestureAnalyzer: PenGestureAnalyzer
    private lateinit var wheelOverlay: WheelOverlay

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notif_waiting)))

        wheelOverlay = WheelOverlay(applicationContext)

        gestureAnalyzer = PenGestureAnalyzer(
            onSingleClick = { runBinding(GestureKind.CLICK) },
            onDoubleClick = { runBinding(GestureKind.DOUBLE_CLICK) },
            onLongPress = { runBinding(GestureKind.LONG_PRESS) },
            onHoverChanged = { hovering -> sendGestureBroadcast(if (hovering) "HOVER_START" else "HOVER_END") }
        )

        Thread {
            val digitizerPath = EventDeviceFinder.findDevicePath(DIGITIZER_DEVICE_NAME)
            if (digitizerPath == null) {
                updateNotification(getString(R.string.notif_not_found, DIGITIZER_DEVICE_NAME))
                return@Thread
            }
            digitizerReader = EPenInputReader(digitizerPath) { type, code, value ->
                gestureAnalyzer.onEvent(type, code, value)
            }
            digitizerReader?.start()
            updateNotification(getString(R.string.notif_active, digitizerPath))

            val presencePath = EventDeviceFinder.findDevicePath(PRESENCE_DEVICE_NAME)
            if (presencePath != null) {
                presenceReader = EPenInputReader(presencePath) { type, code, value ->
                    if (type == "EV_SW" && code == PRESENCE_SWITCH_CODE) {
                        handlePenRemovedSwitch(value != "00000000")
                    }
                }
                presenceReader?.start()
            }
        }.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        digitizerReader?.stop()
        presenceReader?.stop()
        wheelOverlay.dismiss()
        super.onDestroy()
    }

    private fun runBinding(gesture: GestureKind) {
        val action = GestureBindings.load(applicationContext, gesture)
        sendGestureBroadcast(gesture.name)
        ActionExecutor.execute(applicationContext, action, wheelOverlay)
    }

    private fun handlePenRemovedSwitch(switchOn: Boolean) {
        sendGestureBroadcast(if (switchOn) "PEN_REMOVED" else "PEN_INSERTED")
        if (!AppSettings.isAutoStartOnPen(applicationContext)) return
        if (switchOn) wheelOverlay.show() else wheelOverlay.dismiss()
    }

    private fun sendGestureBroadcast(gestureType: String) {
        sendBroadcast(Intent("com.denis.spenfix.GESTURE").putExtra("type", gestureType))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
