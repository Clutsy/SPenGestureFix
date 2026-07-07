package com.denis.spenfix

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Vibrator
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.denis.spenfix.databinding.ActivityTabletModeBinding

class TabletModeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTabletModeBinding
    private var isRunning = false
    private var inputCapture: TabletInputCapture? = null
    private var hidWriter: TabletHidReportWriter? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var smoothedX = 0.5f
    private var smoothedY = 0.5f
    private var lastTouching = false

    private lateinit var vibrator: Vibrator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTabletModeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Make activity full-screen immersive
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUI()

        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

        binding.btnToggleTablet.setOnClickListener {
            if (isRunning) {
                stopTabletMode()
            } else {
                startTabletMode()
            }
        }

        binding.btnExitTablet.setOnClickListener {
            finish()
        }

        binding.fabSettings.setOnClickListener {
            startActivity(Intent(this, TabletSettingsActivity::class.java))
        }

        // Apply grid preferences
        binding.drawingArea.setShowGrid(TabletConfig.getShowGrid(this))
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()
        binding.drawingArea.setShowGrid(TabletConfig.getShowGrid(this))
    }

    override fun onDestroy() {
        stopTabletMode()
        super.onDestroy()
    }

    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        }
    }

    private fun startTabletMode() {
        binding.tvStatus.text = "Configuring USB HID Gadget..."
        binding.statusIndicator.setBackgroundResource(android.R.drawable.presence_video_busy)

        Thread {
            val available = TabletUsbHidGadget.isAvailable()
            if (!available) {
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.tablet_status_error), Toast.LENGTH_LONG).show()
                    binding.tvStatus.text = getString(R.string.tablet_status_error)
                    binding.statusIndicator.setBackgroundResource(android.R.drawable.presence_offline)
                }
                return@Thread
            }

            val success = TabletUsbHidGadget.setup(this)
            runOnUiThread {
                if (success) {
                    initCaptureLoop()
                } else {
                    Toast.makeText(this, "Failed to setup USB HID. Ensure device is rooted.", Toast.LENGTH_LONG).show()
                    binding.tvStatus.text = "USB HID Failed"
                    binding.statusIndicator.setBackgroundResource(android.R.drawable.presence_offline)
                }
            }
        }.start()
    }

    private fun initCaptureLoop() {
        val devicePath = EventDeviceFinder.findDevicePath("sec_e-pen")
        if (devicePath == null) {
            Toast.makeText(this, "S Pen digitizer device not found", Toast.LENGTH_LONG).show()
            binding.tvStatus.text = "Digitizer missing"
            binding.statusIndicator.setBackgroundResource(android.R.drawable.presence_offline)
            if (TabletConfig.getAutoRestoreUsb(this)) {
                TabletUsbHidGadget.teardown()
            }
            return
        }

        hidWriter = TabletHidReportWriter()
        val writerOpen = hidWriter?.open() ?: false
        if (!writerOpen) {
            Toast.makeText(this, "Failed to open HID writer channel", Toast.LENGTH_SHORT).show()
            binding.tvStatus.text = "HID channel open error"
            binding.statusIndicator.setBackgroundResource(android.R.drawable.presence_offline)
            if (TabletConfig.getAutoRestoreUsb(this)) {
                TabletUsbHidGadget.teardown()
            }
            return
        }

        // Setup local smoothing starting positions
        smoothedX = 0.5f
        smoothedY = 0.5f
        lastTouching = false

        // Load configs
        val configCurve = TabletConfig.getPressureCurve(this)
        val configPoints = TabletConfig.getCustomCurvePoints(this)
        val minPressure = TabletConfig.getPressureMin(this)
        val maxPressure = TabletConfig.getPressureMax(this)
        val smoothingFactor = TabletConfig.getSmoothing(this)
        val orient = TabletConfig.getOrientation(this)
        val hapticEnabled = TabletConfig.getHapticFeedback(this)
        val btnAction = TabletConfig.getPenButtonAction(this)
        
        val aspectLock = TabletConfig.getAspectRatioLock(this)
        val monW = TabletConfig.getMonitorWidth(this)
        val monH = TabletConfig.getMonitorHeight(this)

        inputCapture = TabletInputCapture(devicePath) { frame ->
            // 1. Apply Orientation Rotation
            var rotatedX = frame.x
            var rotatedY = frame.y
            
            // Determine active orientation
            val activeOrient = if (orient == OrientationType.AUTO) {
                // Default Note 3 is Portrait. If user holds phone, we can read default landscape.
                // Let's assume Landscape for drawing since drawing tables are landscape.
                OrientationType.LANDSCAPE
            } else {
                orient
            }

            when (activeOrient) {
                OrientationType.PORTRAIT -> {
                    rotatedX = frame.x
                    rotatedY = frame.y
                }
                OrientationType.LANDSCAPE -> {
                    rotatedX = frame.y
                    rotatedY = 1f - frame.x
                }
                OrientationType.LANDSCAPE_INV -> {
                    rotatedX = 1f - frame.y
                    rotatedY = frame.x
                }
                else -> {}
            }

            // Invert axes if requested
            if (TabletConfig.getInvertX(this)) rotatedX = 1f - rotatedX
            if (TabletConfig.getInvertY(this)) rotatedY = 1f - rotatedY

            // 2. Aspect Ratio Correction
            var mappedX = rotatedX
            var mappedY = rotatedY
            if (activeOrient != OrientationType.PORTRAIT && aspectLock && monH > 0) {
                // Monitor AR
                val monAR = monW.toFloat() / monH.toFloat()
                // Phone screen AR (Note 3 is 16:9 1920x1080)
                val phoneAR = 1920f / 1080f
                if (phoneAR > monAR) {
                    val scaleX = monAR / phoneAR
                    mappedX = 0.5f + (rotatedX - 0.5f) * scaleX
                } else if (phoneAR < monAR) {
                    val scaleY = phoneAR / monAR
                    mappedY = 0.5f + (rotatedY - 0.5f) * scaleY
                }
            }

            // 3. Apply Smoothing
            smoothedX = smoothedX + (1f - smoothingFactor) * (mappedX - smoothedX)
            smoothedY = smoothedY + (1f - smoothingFactor) * (mappedY - smoothedY)

            // Clamp coordinate outputs
            val outX = smoothedX.coerceIn(0f, 1f)
            val outY = smoothedY.coerceIn(0f, 1f)

            // 4. Apply Pressure curve
            val finalPressure = PressureCurve.applyWithClamp(frame.pressure, configCurve, minPressure, maxPressure, configPoints)

            // 5. Button and Eraser Action
            var sendBtn = false
            var sendEraser = false

            if (frame.button) {
                when (btnAction) {
                    PenButtonAction.RIGHT_CLICK -> sendBtn = true
                    PenButtonAction.MIDDLE_CLICK -> { /* middle click can be set if descriptor supports. we map to right click button here */ sendBtn = true }
                    PenButtonAction.ERASER -> sendEraser = true
                    PenButtonAction.DISABLED -> {}
                }
            }

            // Haptic trigger on pen touch transition
            if (hapticEnabled && frame.touching && !lastTouching) {
                try { vibrator.vibrate(20) } catch (_: Exception) {}
            }
            lastTouching = frame.touching

            // 6. Write report to USB HID
            hidWriter?.writeReport(
                x = outX,
                y = outY,
                pressure = finalPressure,
                touching = frame.touching,
                button = sendBtn,
                eraser = sendEraser,
                inRange = frame.inRange
            )

            // Update local canvas indicator
            mainHandler.post {
                binding.drawingArea.setPointer(outX, outY, finalPressure, frame.touching, frame.inRange)
                binding.tvPressureValue.text = "P: ${(finalPressure * 100).toInt()}%"
            }
        }

        inputCapture?.start()
        isRunning = true

        binding.tvStatus.text = getString(R.string.tablet_status_active)
        binding.statusIndicator.setBackgroundResource(android.R.drawable.presence_online)
        binding.btnToggleTablet.setIconResource(android.R.drawable.ic_media_pause)
        binding.btnToggleTablet.setBackgroundColor(Color.parseColor("#E040FB"))
    }

    private fun stopTabletMode() {
        if (!isRunning) return
        isRunning = false

        inputCapture?.stop()
        inputCapture = null

        hidWriter?.close()
        hidWriter = null

        Thread {
            if (TabletConfig.getAutoRestoreUsb(this)) {
                TabletUsbHidGadget.teardown()
            }
        }.start()

        binding.tvStatus.text = getString(R.string.tablet_status_ready)
        binding.statusIndicator.setBackgroundResource(android.R.drawable.presence_offline)
        binding.btnToggleTablet.setIconResource(android.R.drawable.ic_media_play)
        binding.btnToggleTablet.setBackgroundColor(Color.parseColor("#6C63FF"))
        binding.drawingArea.setPointer(0f, 0f, 0f, false, false)
        binding.tvPressureValue.text = "P: 0%"
    }
}
