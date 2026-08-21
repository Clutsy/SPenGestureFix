package com.spengesturefix

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

class TabletSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!TabletConfig.isMonitorManuallyConfigured(this)) {
            TabletConfig.detectAndStoreScreenResolution(this)
        }
        setContent {
            SpenFixTheme {
                TabletSettingsComposeScreen(
                    curve = TabletConfig.getPressureCurve(this),
                    customPoints = TabletConfig.getCustomCurvePoints(this),
                    pressureMin = TabletConfig.getPressureMin(this),
                    pressureMax = TabletConfig.getPressureMax(this),
                    orientation = TabletConfig.getOrientation(this),
                    invertX = TabletConfig.getInvertX(this),
                    invertY = TabletConfig.getInvertY(this),
                    aspectLock = TabletConfig.getAspectRatioLock(this),
                    mappingMode = TabletConfig.getMappingMode(this),
                    monitorWidth = TabletConfig.getMonitorWidth(this),
                    monitorHeight = TabletConfig.getMonitorHeight(this),
                    sendRate = TabletConfig.getSendRateHz(this),
                    smoothing = TabletConfig.getSmoothing(this),
                    buttonAction = TabletConfig.getPenButtonAction(this),
                    haptic = TabletConfig.getHapticFeedback(this),
                    showGrid = TabletConfig.getShowGrid(this),
                    autoRestore = TabletConfig.getAutoRestoreUsb(this),
                    onCurveChanged = { TabletConfig.setPressureCurve(this, it) },
                    onCustomPointsChanged = { TabletConfig.setCustomCurvePoints(this, it) },
                    onPressureMinChanged = { TabletConfig.setPressureMin(this, it) },
                    onPressureMaxChanged = { TabletConfig.setPressureMax(this, it) },
                    onOrientationChanged = { TabletConfig.setOrientation(this, it) },
                    onInvertXChanged = { TabletConfig.setInvertX(this, it) },
                    onInvertYChanged = { TabletConfig.setInvertY(this, it) },
                    onAspectLockChanged = { TabletConfig.setAspectRatioLock(this, it) },
                    onMappingModeChanged = { TabletConfig.setMappingMode(this, it) },
                    onMonitorWidthChanged = { TabletConfig.setMonitorWidth(this, it.coerceAtLeast(1)); TabletConfig.markMonitorConfigured(this) },
                    onMonitorHeightChanged = { TabletConfig.setMonitorHeight(this, it.coerceAtLeast(1)); TabletConfig.markMonitorConfigured(this) },
                    onSendRateChanged = { TabletConfig.setSendRateHz(this, it.coerceIn(30, 200)) },
                    onSmoothingChanged = { TabletConfig.setSmoothing(this, it.coerceIn(0f, .9f)) },
                    onButtonActionChanged = { TabletConfig.setPenButtonAction(this, it) },
                    onHapticChanged = { TabletConfig.setHapticFeedback(this, it) },
                    onShowGridChanged = { TabletConfig.setShowGrid(this, it) },
                    onAutoRestoreChanged = { TabletConfig.setAutoRestoreUsb(this, it) },
                    onAutoDetectResolution = { TabletConfig.detectAndStoreScreenResolution(this) },
                    onClose = ::finish
                )
            }
        }
    }
}
