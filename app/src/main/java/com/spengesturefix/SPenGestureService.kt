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
 * Foreground service for the rooted Note 3 Wacom digitizer.
 *
 * The sec_e-pen reader and the w1 presence reader are independent processes.
 * Presence changes update state/UI only; they never stop or gate the digitizer
 * stream. This prevents an insertion event from killing the input pipeline.
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
    private val lastPenInputAt = AtomicLong(0L)
    private lateinit var gestureAnalyzer: PenGestureAnalyzer
    private lateinit var wheelOverlay: WheelOverlay
    private var wakeLock: PowerManager.WakeLock? = null
    private var removeTabletModeListener: (() -> Unit)? = null

    /**
     * Presence fallback: the Note 3 w1 switch does not always fire live, so a
     * pen is treated as extracted while input events keep arriving and as
     * inserted after PEN_IDLE_INSERTED_MS without any pen input. This never
     * touches the digitizer pipeline; it only updates state/UI.
     */
    private val presenceWatchdog = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            if (presenceState.get() != PenPresenceState.INSERTED &&
                isPenIdle(now, lastPenInputAt.get())
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
                if (::gestureAnalyzer.isInitialized) {
                    gestureAnalyzer.cancelPendingGesture()
                }
            }
        }
        // Start the inactivity window at service startup. Using zero here would
        // make elapsedRealtime() look older than five seconds immediately.
        lastPenInputAt.set(SystemClock.elapsedRealtime())

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

        PenRuntimeState.publish(this, serviceActive = true, digitizerActive = false)
        mainHandler.post(presenceWatchdog)
        setupExecutor.execute { startReaders() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startReaders() {
        val digitizerPath = EventDeviceFinder.findDevicePath(DIGITIZER_DEVICE_NAME)
        if (digitizerPath == null) {
            updateNotification(getString(R.string.notif_not_found, DIGITIZER_DEVICE_NAME))
        } else {
            Log.i(TAG, "Starting digitizer reader on $digitizerPath")
            digitizerReader = EPenInputReader(digitizerPath) { type, code, value ->
                // This callback stays on the reader thread. The analyzer only
                // posts low-frequency gesture results to the main looper.
                if (!TabletModeState.isActive) {
                    gestureAnalyzer.onEvent(type, code, value)
                }
                // Physical pen activity means the pen is out of the slot and
                // in use: drive the presence state from real input.
                lastPenInputAt.set(SystemClock.elapsedRealtime())
                if (presenceState.get() != PenPresenceState.REMOVED) {
                    handlePresenceChanged(PenPresenceState.REMOVED)
                }
            }.also { it.start() }
            PenRuntimeState.publish(this, digitizerActive = true)
            updateNotification(getString(R.string.notif_active, digitizerPath))
        }

        val presencePath = EventDeviceFinder.findDevicePath(PRESENCE_DEVICE_NAME)
        if (presencePath == null) {
            Log.w(TAG, "Presence switch $PRESENCE_DEVICE_NAME not found; continuing digitizer-only")
            if (digitizerPath == null) {
                updateNotification(getString(R.string.notif_not_found, DIGITIZER_DEVICE_NAME))
            }
            return
        }

        EventDeviceFinder.readSwitchState(PRESENCE_DEVICE_NAME)?.let { rawState ->
            Log.i(TAG, "Initial presence raw state: $rawState")
            PenPresenceDecoder.decode("EV_SW", PRESENCE_SWITCH_CODE, rawState)?.let(::handlePresenceChanged)
        }
        Log.i(TAG, "Starting presence reader on $presencePath")
        presenceReader = EPenInputReader(presencePath) { type, code, value ->
            val state = PenPresenceDecoder.decode(type, code, value)
            if (state != null) handlePresenceChanged(state)
        }.also { it.start() }
    }

    private fun handlePresenceChanged(state: PenPresenceState) {
        // Presence callbacks come from the root reader threads. Deduplicate
        // before posting: a pen movement can generate hundreds of events and
        // must never flood the main queue with identical state updates.
        if (presenceState.getAndSet(state) == state) return
        // Keep publication, broadcasts, and overlay work on the main looper so
        // a switch event can never delay or consume the digitizer stream.
        mainHandler.post { applyPresenceChanged(state) }
    }

    private fun applyPresenceChanged(state: PenPresenceState) {
        if (state == lastPresence) return
        lastPresence = state
        Log.i(TAG, "Presence changed: $state")
        PenRuntimeState.publish(this, presence = state, digitizerActive = digitizerReader?.isRunning == true)
        sendGestureBroadcast(if (state == PenPresenceState.REMOVED) "PEN_REMOVED" else "PEN_INSERTED")

        when (state) {
            PenPresenceState.REMOVED -> {
                if (!TabletModeState.isActive &&
                    AppSettings.isAutoStartOnPen(applicationContext)
                ) {
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
        // A gesture can already be queued on the main handler when Tablet Mode
        // starts. Check again here so it can never execute in tablet mode.
        if (TabletModeState.isActive) return
        val action = GestureBindings.load(applicationContext, gesture)
        Log.i(TAG, "Gesture recognized: ${gesture.name} -> ${action.type}")
        sendGestureBroadcast(gesture.name)
        // Called on the main handler by PenGestureAnalyzer; expensive branches
        // in ActionExecutor already move shell/screenshot work to a worker.
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
        mainHandler.removeCallbacks(presenceWatchdog)
        // Stop only owned processes. No global process killing is allowed.
        digitizerReader?.stop()
        presenceReader?.stop()
        digitizerReader = null
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
