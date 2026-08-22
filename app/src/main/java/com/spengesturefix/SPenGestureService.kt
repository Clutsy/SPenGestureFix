package com.spengesturefix

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Foreground service for a rooted Wacom digitizer.
 *
 * Presence and digitizer readers are independent. Tablet Mode temporarily owns
 * the digitizer device exclusively, preventing two getevent readers from
 * splitting the same input stream. The presence switch continues separately.
 */
class SPenGestureService : Service() {
    companion object {
        private const val TAG = "SPenGestureService"
        const val CHANNEL_ID = "spen_gesture_service"
        const val NOTIFICATION_ID = 1
        const val DIGITIZER_DEVICE_NAME = "sec_e-pen"
        const val PRESENCE_DEVICE_NAME = "w1"
        const val PRESENCE_SWITCH_CODE = "001a"
        /** No pen input for this long => the pen is considered inserted. */
        const val PEN_IDLE_INSERTED_MS = 5_000L
        private const val WATCHDOG_INTERVAL_MS = 1_000L

        /** Strictly greater than five seconds, matching the user-facing rule. */
        @JvmStatic
        fun isPenIdle(now: Long, lastInputAt: Long): Boolean =
            lastInputAt > 0L && now - lastInputAt > PEN_IDLE_INSERTED_MS
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val setupExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SpenServiceSetup").apply { isDaemon = true }
    }
    @Volatile private var digitizerReader: EPenInputReader? = null
    @Volatile private var presenceReader: EPenInputReader? = null
    @Volatile private var lastPresence = PenPresenceState.UNKNOWN
    private val presenceState = AtomicReference(PenPresenceState.UNKNOWN)
    private val digitizerLock = Any()
    @Volatile private var serviceRunning = false
    private lateinit var gestureAnalyzer: PenGestureAnalyzer
    private lateinit var wheelOverlay: WheelOverlay
    private var wakeLock: PowerManager.WakeLock? = null
    private var removeTabletModeListener: (() -> Unit)? = null

    /**
     * Presence fallback: a pen is treated as extracted while input events keep
     * arriving and as inserted after five seconds without pen input. This only
     * updates state; it never blocks or gates the active reader.
     */
    private val presenceWatchdog = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            if (presenceState.get() != PenPresenceState.INSERTED &&
                isPenIdle(now, PenInputActivity.lastInputAt())
            ) {
                handlePresenceChanged(PenPresenceState.INSERTED)
            }
            mainHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notif_starting)))
        acquireWakeLock()
        wheelOverlay = WheelOverlay(applicationContext)
        removeTabletModeListener = TabletModeState.addListener { active ->
            if (active) {
                wheelOverlay.dismiss()
                if (::gestureAnalyzer.isInitialized) gestureAnalyzer.cancelPendingGesture()
                // The reader owns a root process and may need a short wait
                // during shutdown. Keep that handoff off the main/UI looper;
                // Tablet Mode waits for the release latch before opening the
                // same kernel device.
                setupExecutor.execute { stopNormalDigitizerReader() }
            } else {
                // Tablet capture has released the shared input device. Restore
                // normal gesture handling after any queued stop has completed.
                if (serviceRunning) setupExecutor.execute { startDigitizerReader() }
            }
        }
        PenInputActivity.mark()

        gestureAnalyzer = PenGestureAnalyzer(
            onSingleClick = { runBinding(GestureKind.CLICK) },
            onDoubleClick = { runBinding(GestureKind.DOUBLE_CLICK) },
            onLongPress = { runBinding(GestureKind.LONG_PRESS) },
            onHoverChanged = { hovering ->
                if (!TabletModeState.isActive) {
                    sendGestureBroadcast(if (hovering) "HOVER_START" else "HOVER_END")
                }
            }
        )

        serviceRunning = true
        TabletModeState.setNormalInputOwner(true)
        PenRuntimeState.publish(this, serviceActive = true, digitizerActive = false)
        mainHandler.post(presenceWatchdog)
        setupExecutor.execute { startReaders() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startReaders() {
        startDigitizerReader()
        startPresenceReader()
    }

    private fun startDigitizerReader() {
        if (TabletModeState.isActive || digitizerReader?.isRunning == true) return
        val digitizerPath = EventDeviceFinder.findDevicePath(DIGITIZER_DEVICE_NAME)
        if (digitizerPath == null) {
            updateNotification(getString(R.string.notif_not_found, DIGITIZER_DEVICE_NAME))
            PenRuntimeState.publish(this, digitizerActive = false)
            return
        }

        Log.i(TAG, "Starting digitizer reader on $digitizerPath")
        val reader = EPenInputReader(digitizerPath) { type, code, value ->
            // This callback stays on the reader thread. The analyzer posts only
            // low-frequency gesture results to the main looper.
            if (!TabletModeState.isActive) gestureAnalyzer.onEvent(type, code, value)

            PenInputActivity.mark()
            if (presenceState.get() != PenPresenceState.REMOVED) {
                handlePresenceChanged(PenPresenceState.REMOVED)
            }
        }
        synchronized(digitizerLock) {
            if (TabletModeState.isActive || digitizerReader?.isRunning == true) return
            digitizerReader = reader
            reader.start()
        }
        PenRuntimeState.publish(this, digitizerActive = true)
        updateNotification(getString(R.string.notif_active, digitizerPath))
    }

    private fun startPresenceReader() {
        if (presenceReader?.isRunning == true) return
        val presencePath = EventDeviceFinder.findDevicePath(PRESENCE_DEVICE_NAME)
        if (presencePath == null) {
            Log.w(TAG, "Presence switch $PRESENCE_DEVICE_NAME not found; continuing digitizer-only")
            return
        }

        EventDeviceFinder.readSwitchState(PRESENCE_DEVICE_NAME)?.let { rawState ->
            Log.i(TAG, "Initial presence raw state: $rawState")
            PenPresenceDecoder.decode("EV_SW", PRESENCE_SWITCH_CODE, rawState)
                ?.let(::handlePresenceChanged)
        }
        Log.i(TAG, "Starting presence reader on $presencePath")
        presenceReader = EPenInputReader(presencePath) { type, code, value ->
            PenPresenceDecoder.decode(type, code, value)?.let(::handlePresenceChanged)
        }.also { it.start() }
    }

    private fun stopNormalDigitizerReader(waitForTermination: Boolean = true) {
        val reader = synchronized(digitizerLock) {
            val current = digitizerReader
            digitizerReader = null
            current
        }
        if (reader != null) {
            if (waitForTermination) reader.stopAndWait(750L) else reader.stop()
        }
        PenRuntimeState.publish(this, digitizerActive = false)
        TabletModeState.markNormalInputReleased()
    }

    private fun handlePresenceChanged(state: PenPresenceState) {
        // Presence callbacks come from root reader threads. Deduplicate before
        // posting so a switch event cannot flood the main queue.
        if (presenceState.getAndSet(state) == state) return
        mainHandler.post { applyPresenceChanged(state) }
    }

    private fun applyPresenceChanged(state: PenPresenceState) {
        if (state == lastPresence) return
        lastPresence = state
        Log.i(TAG, "Presence changed: $state")
        PenRuntimeState.publish(
            this,
            presence = state,
            digitizerActive = digitizerReader?.isRunning == true
        )
        sendGestureBroadcast(if (state == PenPresenceState.REMOVED) "PEN_REMOVED" else "PEN_INSERTED")

        when (state) {
            PenPresenceState.REMOVED -> {
                if (!TabletModeState.isActive && AppSettings.isAutoStartOnPen(applicationContext)) {
                    wheelOverlay.show()
                }
                updateNotification(getString(R.string.notif_pen_extracted))
            }
            PenPresenceState.INSERTED -> {
                wheelOverlay.dismiss()
                updateNotification(getString(R.string.notif_pen_inserted))
            }
            PenPresenceState.UNKNOWN -> Unit
        }
    }

    private fun runBinding(gesture: GestureKind) {
        if (TabletModeState.isActive) return
        val action = GestureBindings.load(applicationContext, gesture)
        Log.i(TAG, "Gesture recognized: ${gesture.name} -> ${action.type}")
        sendGestureBroadcast(gesture.name)
        ActionExecutor.execute(applicationContext, action, wheelOverlay)
    }

    private fun sendGestureBroadcast(gestureType: String) {
        sendBroadcast(Intent("com.spengesturefix.GESTURE").apply {
            setPackage(packageName)
            putExtra("type", gestureType)
        })
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "SPenFix::Digitizer"
            ).apply { acquire() }
        } catch (error: Exception) {
            Log.w(TAG, "Could not acquire wake lock", error)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        } finally {
            wakeLock = null
        }
    }

    override fun onDestroy() {
        serviceRunning = false
        TabletModeState.setNormalInputOwner(false)
        mainHandler.removeCallbacks(presenceWatchdog)
        stopNormalDigitizerReader(waitForTermination = false)
        presenceReader?.stop()
        presenceReader = null
        gestureAnalyzer.close()
        setupExecutor.shutdownNow()
        removeTabletModeListener?.invoke()
        removeTabletModeListener = null
        mainHandler.post { wheelOverlay.dismiss() }
        PenRuntimeState.publish(this, serviceActive = false, digitizerActive = false)
        releaseWakeLock()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)?.notify(
            NOTIFICATION_ID,
            buildNotification(text)
        )
    }
}
