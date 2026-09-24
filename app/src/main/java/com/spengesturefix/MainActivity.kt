package com.spengesturefix

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.os.LocaleListCompat
import androidx.core.view.WindowCompat
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {
    private var presence by mutableStateOf(PenRuntimeState.presence)
    private var serviceActive by mutableStateOf(PenRuntimeState.serviceActive)
    private var digitizerActive by mutableStateOf(PenRuntimeState.digitizerActive)
    private var rootChecked by mutableStateOf<Boolean?>(null)
    private var overlayGranted by mutableStateOf(false)
    private var notificationGranted by mutableStateOf(true)
    private var amoled by mutableStateOf(false)
    private var autoStart by mutableStateOf(true)
    private var batterySaver by mutableStateOf(true)
    private var languageCode by mutableStateOf<String?>(null)
    private var gestures by mutableStateOf<Map<GestureKind, PenAction>>(emptyMap())
    private var wheelSlots by mutableStateOf<List<PenAction>>(emptyList())
    private var wheelColor by mutableStateOf(androidx.compose.ui.graphics.Color(0xFF29B6F6))
    private var wheelStyle by mutableStateOf(WheelStyle.MODERN)
    private var wheelSlotCount by mutableStateOf(WheelConfig.DEFAULT_SLOT_COUNT)
    private val startPending = AtomicBoolean(false)

    private companion object {
        const val SOUND_OPEN = "open"
        const val SOUND_CLOSE = "close"
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            notificationGranted = granted
            if (granted && startPending.compareAndSet(true, false)) startGestureService()
        }

    /** SAF pickers for the custom wheel open/close sounds (SpenCommand port). */
    private var pendingSoundTarget: Boolean? = null
    private val wheelSoundPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val open = pendingSoundTarget ?: return@registerForActivityResult
            pendingSoundTarget = null
            if (uri != null) {
                val local = WheelSoundStore.importSound(this, uri, open)
                if (local != null) {
                    if (open) WheelSoundStore.setOpenSound(this, local)
                    else WheelSoundStore.setCloseSound(this, local)
                    // Recompose the sounds page so the new file name shows
                    // immediately (the store persists but Compose cannot see it).
                    refreshState()
                }
            }
        }

    private fun pickWheelSound(open: Boolean) {
        pendingSoundTarget = open
        try {
            wheelSoundPicker.launch(arrayOf("audio/*"))
        } catch (_: Exception) {
            pendingSoundTarget = null
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != PenRuntimeState.ACTION_STATUS) return
            serviceActive = intent.getBooleanExtra(
                PenRuntimeState.EXTRA_SERVICE_ACTIVE,
                serviceActive
            )
            digitizerActive = intent.getBooleanExtra(
                PenRuntimeState.EXTRA_DIGITIZER,
                digitizerActive
            )
            presence = intent.getStringExtra(PenRuntimeState.EXTRA_PRESENCE)
                ?.let { runCatching { PenPresenceState.valueOf(it) }.getOrNull() }
                ?: presence
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
        registerStatusReceiver()
        refreshState()

        setContent {
            SpenFixTheme(amoled = AppSettings.isAmoled(this)) {
                MainComposeScreen(
                    presence = presence,
                    serviceActive = serviceActive,
                    digitizerActive = digitizerActive,
                    rootChecked = rootChecked,
                    overlayGranted = overlayGranted,
                    notificationGranted = notificationGranted,
                    amoled = amoled,
                    autoStart = autoStart,
                    batterySaver = batterySaver,
                    languageCode = languageCode,
                    gestures = gestures,
                    wheelSlots = wheelSlots,
                    wheelColor = wheelColor,
                    wheelStyle = wheelStyle,
                    wheelSlotCount = wheelSlotCount,
                    onCheckRoot = ::checkRoot,
                    onRequestOverlay = ::requestOverlayPermission,
                    onStartService = ::requestPermissionsAndStart,
                    onStopService = ::stopGestureService,
                    onAmoledChanged = {
                        amoled = it
                        AppSettings.setAmoled(this, it)
                    },
                    onAutoStartChanged = {
                        autoStart = it
                        AppSettings.setAutoStartOnPen(this, it)
                        // Extraction is an input event, not an Android broadcast:
                        // enabling this option must ensure the listener is already
                        // alive before the next physical extraction.
                        if (it && !serviceActive) requestPermissionsAndStart()
                    },
                    onBatterySaverChanged = {
                        batterySaver = it
                        AppSettings.setBatterySaver(this, it)
                    },
                    onLanguageChanged = ::setLanguage,
                    onActionPicked = ::saveBinding,
                    onWheelColorChanged = {
                        wheelColor = it
                        WheelConfig.setWheelColor(this, it)
                    },
                    onWheelStyleChanged = {
                        wheelStyle = it
                        WheelStyle.save(this, it)
                    },
                    onWheelSlotCountChanged = { count ->
                        WheelConfig.resizeSlots(this, count)
                        wheelSlotCount = count
                        wheelSlots = WheelConfig.loadSlots(this)
                    },
                    onPickWheelSound = { open -> pickWheelSound(open) },
                    onClearWheelSound = { open ->
                        if (open) WheelSoundStore.setOpenSound(this, null)
                        else WheelSoundStore.setCloseSound(this, null)
                    },
                    onMoveWheelSlot = { from, to ->
                        WheelConfig.moveSlot(this, from, to)
                        wheelSlots = WheelConfig.loadSlots(this)
                    },
                    onOpenNotes = {
                        startActivity(Intent(this, NotesListActivity::class.java))
                    },
                    onOpenTablet = {
                        startActivity(Intent(this, TabletModeActivity::class.java))
                    },
                    onOpenTabletSettings = {
                        startActivity(Intent(this, TabletSettingsActivity::class.java))
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshState()
    }

    override fun onDestroy() {
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) { }
        super.onDestroy()
    }

    private fun registerStatusReceiver() {
        val filter = IntentFilter(PenRuntimeState.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(statusReceiver, filter)
        }
    }

    private fun refreshState() {
        overlayGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
        notificationGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        amoled = AppSettings.isAmoled(this)
        autoStart = AppSettings.isAutoStartOnPen(this)
        batterySaver = AppSettings.isBatterySaver(this)
        languageCode = AppSettings.language(this)
        presence = PenRuntimeState.presence
        serviceActive = PenRuntimeState.serviceActive
        digitizerActive = PenRuntimeState.digitizerActive
        gestures = GestureKind.values().associateWith { GestureBindings.load(this, it) }
        wheelSlots = WheelConfig.loadSlots(this)
        wheelColor = WheelConfig.getWheelColor(this)
        wheelStyle = WheelStyle.load(this)
        wheelSlotCount = WheelConfig.slotCount(this)
    }

    private fun saveBinding(target: BindingTarget, action: PenAction) {
        when (target) {
            is BindingTarget.Gesture -> GestureBindings.save(this, target.kind, action)
            is BindingTarget.WheelSlot -> WheelConfig.saveSlot(this, target.index, action)
        }
        gestures = GestureKind.values().associateWith { GestureBindings.load(this, it) }
        wheelSlots = WheelConfig.loadSlots(this)
    }

    private fun setLanguage(code: String?) {
        AppSettings.setLanguage(this, code)
        languageCode = code
        val locales = if (code.isNullOrBlank()) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(code)
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }

    private fun checkRoot() {
        rootChecked = null
        Thread {
            val hasRoot = try {
                val process = ProcessBuilder("su", "-c", "id")
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                process.waitFor() == 0 && output.contains("uid=0")
            } catch (_: Exception) {
                false
            }
            runOnUiThread { rootChecked = hasRoot }
        }.apply { isDaemon = true; start() }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } else {
            overlayGranted = true
        }
    }

    private fun requestPermissionsAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationGranted) {
            startPending.set(true)
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        startGestureService()
    }

    private fun startGestureService() {
        val intent = Intent(this, SPenGestureService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
        serviceActive = true
    }

    private fun stopGestureService() {
        stopService(Intent(this, SPenGestureService::class.java))
        serviceActive = false
        digitizerActive = false
    }
}
