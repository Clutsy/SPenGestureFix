package com.spengesturefix

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.net.NetworkInterface
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class TabletModeActivity : ComponentActivity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val emptyFrame = TabletFrame(0f, 0f, 0f, false, false, false)
    private val pendingFrame = AtomicReference<TabletFrame?>(emptyFrame)
    private val uiUpdateScheduled = AtomicBoolean(false)
    private val tabletSessionId = AtomicInteger(0)
    private val sessionRunning = AtomicBoolean(false)
    private val activityAlive = AtomicBoolean(false)
    private val modeReleased = AtomicBoolean(false)
    private val captureStopping = AtomicBoolean(false)
    private val sessionLock = Any()
    private var frame by mutableStateOf(emptyFrame)
    private var running by mutableStateOf(false)
    private var status by mutableStateOf("")
    private var ipAddress by mutableStateOf<String?>(null)
    private var framesSent by mutableStateOf(0L)
    private var previewConnected by mutableStateOf(false)
    @Volatile private var inputCapture: TabletInputCapture? = null
    private var lastTouching = false
    private var smoothedX = 0.5f
    private var smoothedY = 0.5f

    @Suppress("DEPRECATION")
    private val vibrator by lazy { getSystemService(Context.VIBRATOR_SERVICE) as Vibrator }

    private val frameRunnable = object : Runnable {
        override fun run() {
            pendingFrame.getAndSet(null)?.let { frame = it }
            framesSent = TabletNetworkServer.framesSent()
            uiUpdateScheduled.set(false)
            if (pendingFrame.get() != null) publishFrameToUi()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activityAlive.set(true)
        // Tablet Mode owns pen behavior while this Activity is alive. The
        // service can keep presence monitoring, but normal actions are gated.
        TabletModeState.enter()
        // Older ROMs can surface the physical pen key as BACK. Only the
        // explicit on-screen Exit control may leave this Activity.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })

        if (!TabletConfig.isMonitorManuallyConfigured(this)) {
            TabletConfig.detectAndStoreScreenResolution(this)
        }
        // The default target is a horizontal monitor. A manually configured
        // portrait target is still supported without changing the mapping API.
        requestedOrientation = if (
            TabletConfig.getMonitorWidth(this) >= TabletConfig.getMonitorHeight(this)
        ) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUi()
        ipAddress = getLocalIp()
        status = getString(R.string.tablet_status_ready)
        setContent {
            SpenFixTheme(amoled = AppSettings.isAmoled(this)) {
                TabletModeComposeScreen(
                    frame = frame,
                    running = running,
                    status = status,
                    ip = ipAddress,
                    showGrid = TabletConfig.getShowGrid(this),
                    framesSent = framesSent,
                    pcConnected = TabletNetworkServer.isClientConnected,
                    previewConnected = previewConnected,
                    onToggle = { if (running) stopTabletMode() else startTabletMode() },
                    onSettings = { startActivity(Intent(this, TabletSettingsActivity::class.java)) },
                    onExit = ::finish
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        previewConnected = TabletPreviewServer.isClientConnected
    }

    override fun onDestroy() {
        activityAlive.set(false)
        // stopTabletMode releases TabletModeState synchronously when no reader
        // exists, or after the background reader handoff has completed.
        stopTabletMode()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun hideSystemUi() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun startTabletMode() {
        if (running || captureStopping.get()) return
        // The same Activity can stop and start a session more than once. If
        // the previous session released ownership, acquire it again before
        // creating a new capture reader.
        if (modeReleased.compareAndSet(true, false)) TabletModeState.enter()
        running = true
        sessionRunning.set(true)
        mainHandler.removeCallbacks(previewPollRunnable)
        mainHandler.postDelayed(previewPollRunnable, 500L)
        val session = tabletSessionId.incrementAndGet()
        status = getString(R.string.tablet_status_starting)
        val config = TabletRunConfig.from(this)
        Thread {
            if (PenRuntimeState.serviceActive && !TabletModeState.awaitNormalInputReleased(2_000L)) {
                if (isSessionActive(session)) {
                    runOnUiThread {
                        if (isSessionActive(session)) {
                            running = false
                            sessionRunning.set(false)
                            status = getString(R.string.tablet_status_waiting)
                        }
                    }
                }
                return@Thread
            }
            val devicePath = EventDeviceFinder.findDevicePath(SPenGestureService.DIGITIZER_DEVICE_NAME)
            if (devicePath == null) {
                if (!isSessionActive(session)) return@Thread
                runOnUiThread {
                    if (isSessionActive(session)) {
                        running = false
                        sessionRunning.set(false)
                        status = getString(R.string.tablet_status_error_digitizer)
                        Toast.makeText(this, R.string.tablet_status_error_digitizer, Toast.LENGTH_LONG).show()
                    }
                }
                return@Thread
            }
            if (!isSessionActive(session)) return@Thread
            val capabilities = EventDeviceFinder.readCapabilities(devicePath)
            smoothedX = 0.5f
            smoothedY = 0.5f
            lastTouching = false

            synchronized(sessionLock) {
                if (!isSessionActive(session)) return@Thread
                TabletNetworkServer.start(
                    onStatusChange = { connected ->
                        if (isSessionActive(session)) {
                            runOnUiThread {
                                if (isSessionActive(session)) {
                                    status = if (connected) {
                                        getString(R.string.tablet_status_active)
                                    } else {
                                        getString(R.string.tablet_status_waiting)
                                    }
                                    framesSent = TabletNetworkServer.framesSent()
                                }
                            }
                        }
                    },
                    reportRateHz = config.sendRateHz,
                    sourceWidth = config.sourceWidth,
                    sourceHeight = config.sourceHeight,
                    sourceRotation = config.axisRotation,
                    orientation = config.orientation.name.lowercase(Locale.US)
                )
                if (!isSessionActive(session)) {
                    TabletNetworkServer.stop()
                    return@Thread
                }

                inputCapture = TabletInputCapture(devicePath, capabilities) callback@{ rawFrame ->
                    if (!isSessionActive(session)) return@callback
                    PenInputActivity.mark()
                    var x = rawFrame.x
                    var y = rawFrame.y
                    val oriented = TabletConfig.mapCoordinates(x, y, config.axisRotation)
                    x = oriented.first
                    y = oriented.second
                    if (config.invertX) x = 1f - x
                    if (config.invertY) y = 1f - y

                    if (config.mappingMode == MappingMode.CUSTOM_AREA) {
                        val area = config.customArea
                        x = area[0] + x * (area[2] - area[0])
                        y = area[1] + y * (area[3] - area[1])
                    }

                    if (config.aspectLock && config.monitorHeight > 0 && config.monitorWidth > 0 &&
                        config.orientation != OrientationType.PORTRAIT
                    ) {
                        val monitorAspect = config.monitorWidth.toFloat() / config.monitorHeight
                        val sourceAspect = config.sourceWidth.toFloat() / config.sourceHeight
                        if (sourceAspect > monitorAspect) {
                            x = 0.5f + (x - 0.5f) * (monitorAspect / sourceAspect)
                        } else if (sourceAspect < monitorAspect) {
                            y = 0.5f + (y - 0.5f) * (sourceAspect / monitorAspect)
                        }
                    }

                    smoothedX += (1f - config.smoothing) * (x - smoothedX)
                    smoothedY += (1f - config.smoothing) * (y - smoothedY)
                    val pressure = PressureCurve.applyWithClamp(
                        rawFrame.pressure,
                        config.pressureCurve,
                        config.pressureMin,
                        config.pressureMax,
                        config.customPoints
                    )
                    val mapped = rawFrame.copy(
                        x = smoothedX.coerceIn(0f, 1f),
                        y = smoothedY.coerceIn(0f, 1f),
                        pressure = pressure
                    )

                    if (config.haptic && mapped.touching && !lastTouching) {
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                vibrator.vibrate(
                                    VibrationEffect.createOneShot(
                                        18,
                                        VibrationEffect.DEFAULT_AMPLITUDE
                                    )
                                )
                            } else {
                                @Suppress("DEPRECATION")
                                vibrator.vibrate(18)
                            }
                        } catch (_: Exception) { }
                    }
                    lastTouching = mapped.touching
                    val buttonFlags = mapPenButtonAction(config.buttonAction, mapped.button)
                    TabletNetworkServer.sendFrame(
                        mapped.x,
                        mapped.y,
                        mapped.pressure,
                        mapped.touching,
                        buttonFlags.rightButton,
                        buttonFlags.eraser,
                        mapped.inRange,
                        buttonFlags.middleButton
                    )
                    pendingFrame.set(mapped)
                    publishFrameToUi()
                }
                if (!isSessionActive(session)) {
                    inputCapture?.stop()
                    inputCapture = null
                    TabletNetworkServer.stop()
                    return@Thread
                }
                inputCapture?.start()
                if (!isSessionActive(session)) {
                    inputCapture?.stop()
                    inputCapture = null
                    TabletNetworkServer.stop()
                    return@Thread
                }
                TabletPreviewServer.start()
            }
            runOnUiThread {
                if (isSessionActive(session)) {
                    status = getString(R.string.tablet_status_waiting)
                    ipAddress = getLocalIp()
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    private fun isSessionActive(session: Int): Boolean =
        activityAlive.get() && sessionRunning.get() && tabletSessionId.get() == session

    /**
     * Keeps the preview connection badge and live frame counts fresh while a
     * session runs. The poll is cheap (volatile reads) and only touches
     * Compose state when the value actually changes.
     */
    private val previewPollRunnable = object : Runnable {
        override fun run() {
            if (!sessionRunning.get()) return
            previewConnected = TabletPreviewServer.isClientConnected
            framesSent = TabletNetworkServer.framesSent()
            mainHandler.postDelayed(this, 500L)
        }
    }

    private fun publishFrameToUi() {
        if (uiUpdateScheduled.compareAndSet(false, true)) {
            mainHandler.postDelayed(frameRunnable, 16L)
        }
    }

    private fun stopTabletMode() {
        val capture: TabletInputCapture?
        synchronized(sessionLock) {
            sessionRunning.set(false)
            tabletSessionId.incrementAndGet()
            running = false
            capture = inputCapture
            inputCapture = null
            TabletNetworkServer.stop()
        }
        TabletPreviewServer.stop()
        pendingFrame.set(emptyFrame)
        frame = emptyFrame
        ipAddress = getLocalIp()
        status = getString(R.string.tablet_status_ready)
        previewConnected = false

        if (capture == null) {
            if (!captureStopping.get()) releaseTabletModeState()
            return
        }
        if (!captureStopping.compareAndSet(false, true)) return
        Thread {
            try {
                capture.stopAndWait(750L)
            } finally {
                captureStopping.set(false)
                releaseTabletModeState()
            }
        }.apply {
            name = "TabletCaptureStop"
            isDaemon = true
            start()
        }
    }

    private fun releaseTabletModeState() {
        if (modeReleased.compareAndSet(false, true)) {
            TabletModeState.exit()
        }
    }

    private fun getLocalIp(): String? = try {
        NetworkInterface.getNetworkInterfaces()?.toList()
            ?.flatMap { it.inetAddresses.toList() }
            ?.firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains('.') == true }
            ?.hostAddress
    } catch (_: Exception) { null }

    private data class TabletRunConfig(
        val pressureCurve: PressureCurveType,
        val customPoints: List<Float>,
        val pressureMin: Float,
        val pressureMax: Float,
        val smoothing: Float,
        val orientation: OrientationType,
        val axisRotation: Int,
        val invertX: Boolean,
        val invertY: Boolean,
        val aspectLock: Boolean,
        val monitorWidth: Int,
        val monitorHeight: Int,
        val mappingMode: MappingMode,
        val customArea: FloatArray,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val buttonAction: PenButtonAction,
        val haptic: Boolean,
        val sendRateHz: Int
    ) {
        companion object {
            fun from(context: Context): TabletRunConfig {
                val source = TabletConfig.detectScreenResolution(context) ?: (1920 to 1080)
                val selectedOrientation = TabletConfig.getOrientation(context)
                val monitorWidth = TabletConfig.getMonitorWidth(context)
                val monitorHeight = TabletConfig.getMonitorHeight(context)
                val targetLandscape = monitorWidth >= monitorHeight
                val resolvedOrientation = if (selectedOrientation == OrientationType.AUTO) {
                    if (targetLandscape) OrientationType.LANDSCAPE else OrientationType.PORTRAIT
                } else {
                    selectedOrientation
                }
                return TabletRunConfig(
                    pressureCurve = TabletConfig.getPressureCurve(context),
                    customPoints = TabletConfig.getCustomCurvePoints(context),
                    pressureMin = TabletConfig.getPressureMin(context),
                    pressureMax = TabletConfig.getPressureMax(context),
                    smoothing = TabletConfig.getSmoothing(context),
                    orientation = resolvedOrientation,
                    axisRotation = TabletConfig.coordinateRotation(
                        context,
                        selectedOrientation,
                        targetLandscape
                    ),
                    invertX = TabletConfig.getInvertX(context),
                    invertY = TabletConfig.getInvertY(context),
                    aspectLock = TabletConfig.getAspectRatioLock(context),
                    monitorWidth = monitorWidth,
                    monitorHeight = monitorHeight,
                    mappingMode = TabletConfig.getMappingMode(context),
                    customArea = TabletConfig.getCustomArea(context),
                    sourceWidth = source.first.coerceAtLeast(1),
                    sourceHeight = source.second.coerceAtLeast(1),
                    buttonAction = TabletConfig.getPenButtonAction(context),
                    haptic = TabletConfig.getHapticFeedback(context),
                    sendRateHz = TabletConfig.getSendRateHz(context)
                )
            }
        }
    }
}
