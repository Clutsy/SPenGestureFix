package com.denis.spenfix

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class TabletModeActivity : ComponentActivity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val emptyFrame = TabletFrame(0f, 0f, 0f, false, false, false)
    private val pendingFrame = AtomicReference<TabletFrame?>(emptyFrame)
    private val uiUpdateScheduled = AtomicBoolean(false)
    private var frame by mutableStateOf(emptyFrame)
    private var running by mutableStateOf(false)
    private var status by mutableStateOf("")
    private var ipAddress by mutableStateOf<String?>(null)
    private var inputCapture: TabletInputCapture? = null
    private var lastTouching = false
    private var smoothedX = 0.5f
    private var smoothedY = 0.5f

    @Suppress("DEPRECATION")
    private val vibrator by lazy { getSystemService(Context.VIBRATOR_SERVICE) as Vibrator }

    private val frameRunnable = object : Runnable {
        override fun run() {
            pendingFrame.getAndSet(null)?.let { frame = it }
            uiUpdateScheduled.set(false)
            if (pendingFrame.get() != null) publishFrameToUi()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUi()
        ipAddress = getLocalIp()
        status = getString(R.string.tablet_status_ready)
        setContent {
            SpenFixTheme {
                TabletModeComposeScreen(
                    frame = frame,
                    running = running,
                    status = status,
                    ip = ipAddress,
                    showGrid = TabletConfig.getShowGrid(this),
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
    }

    override fun onDestroy() {
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
        if (running) return
        running = true
        status = getString(R.string.tablet_status_starting)
        val config = TabletRunConfig.from(this)
        Thread {
            val devicePath = EventDeviceFinder.findDevicePath(SPenGestureService.DIGITIZER_DEVICE_NAME)
            if (devicePath == null) {
                runOnUiThread {
                    running = false
                    status = getString(R.string.tablet_status_error_digitizer)
                    Toast.makeText(this, R.string.tablet_status_error_digitizer, Toast.LENGTH_LONG).show()
                }
                return@Thread
            }
            val capabilities = EventDeviceFinder.readCapabilities(devicePath)
            smoothedX = 0.5f
            smoothedY = 0.5f
            lastTouching = false

            TabletNetworkServer.start { connected ->
                runOnUiThread {
                    status = if (connected) getString(R.string.tablet_status_active)
                    else getString(R.string.tablet_status_waiting)
                }
            }

            inputCapture = TabletInputCapture(devicePath, capabilities) { rawFrame ->
                var x = rawFrame.x
                var y = rawFrame.y
                when (config.orientation) {
                    OrientationType.LANDSCAPE -> { val oldX = x; x = y; y = 1f - oldX }
                    OrientationType.LANDSCAPE_INV -> { val oldX = x; x = 1f - y; y = oldX }
                    else -> Unit
                }
                if (config.invertX) x = 1f - x
                if (config.invertY) y = 1f - y

                if (config.aspectLock && config.monitorHeight > 0 && config.monitorWidth > 0 &&
                    config.orientation != OrientationType.PORTRAIT
                ) {
                    val monitorAspect = config.monitorWidth.toFloat() / config.monitorHeight
                    val phoneAspect = 16f / 9f
                    if (phoneAspect > monitorAspect) x = 0.5f + (x - 0.5f) * (monitorAspect / phoneAspect)
                    else if (phoneAspect < monitorAspect) y = 0.5f + (y - 0.5f) * (phoneAspect / monitorAspect)
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
                            vibrator.vibrate(VibrationEffect.createOneShot(18, VibrationEffect.DEFAULT_AMPLITUDE))
                        } else {
                            @Suppress("DEPRECATION")
                            vibrator.vibrate(18)
                        }
                    } catch (_: Exception) { }
                }
                lastTouching = mapped.touching
                val sendButton = mapped.button && config.buttonAction != PenButtonAction.DISABLED
                val eraser = mapped.button && config.buttonAction == PenButtonAction.ERASER
                TabletNetworkServer.sendFrame(
                    mapped.x, mapped.y, mapped.pressure, mapped.touching,
                    sendButton, eraser, mapped.inRange
                )
                pendingFrame.set(mapped)
                publishFrameToUi()
            }
            inputCapture?.start()
            runOnUiThread {
                status = getString(R.string.tablet_status_waiting)
                ipAddress = getLocalIp()
            }
        }.apply { isDaemon = true; start() }
    }

    private fun publishFrameToUi() {
        if (uiUpdateScheduled.compareAndSet(false, true)) {
            mainHandler.postDelayed(frameRunnable, 16L)
        }
    }

    private fun stopTabletMode() {
        if (!running && inputCapture == null) return
        running = false
        inputCapture?.stop()
        inputCapture = null
        TabletNetworkServer.stop()
        pendingFrame.set(TabletFrame(0f, 0f, 0f, false, false, false))
        frame = pendingFrame.get() ?: emptyFrame
        ipAddress = getLocalIp()
        status = getString(R.string.tablet_status_ready)
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
        val invertX: Boolean,
        val invertY: Boolean,
        val aspectLock: Boolean,
        val monitorWidth: Int,
        val monitorHeight: Int,
        val buttonAction: PenButtonAction,
        val haptic: Boolean
    ) {
        companion object {
            fun from(context: Context) = TabletRunConfig(
                pressureCurve = TabletConfig.getPressureCurve(context),
                customPoints = TabletConfig.getCustomCurvePoints(context),
                pressureMin = TabletConfig.getPressureMin(context),
                pressureMax = TabletConfig.getPressureMax(context),
                smoothing = TabletConfig.getSmoothing(context),
                orientation = TabletConfig.getOrientation(context).let {
                    if (it == OrientationType.AUTO) OrientationType.LANDSCAPE else it
                },
                invertX = TabletConfig.getInvertX(context),
                invertY = TabletConfig.getInvertY(context),
                aspectLock = TabletConfig.getAspectRatioLock(context),
                monitorWidth = TabletConfig.getMonitorWidth(context),
                monitorHeight = TabletConfig.getMonitorHeight(context),
                buttonAction = TabletConfig.getPenButtonAction(context),
                haptic = TabletConfig.getHapticFeedback(context)
            )
        }
    }
}
