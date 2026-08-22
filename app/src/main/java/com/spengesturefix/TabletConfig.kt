package com.spengesturefix

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.Surface
import android.view.WindowManager
import org.json.JSONArray

enum class PressureCurveType { LINEAR, SOFT, FIRM, S_CURVE, CUSTOM }
enum class OrientationType { AUTO, PORTRAIT, LANDSCAPE, LANDSCAPE_INV }
enum class MappingMode { FULL_SCREEN, CUSTOM_AREA }
enum class PenButtonAction { RIGHT_CLICK, MIDDLE_CLICK, ERASER, DISABLED }

data class TabletButtonFlags(
    val rightButton: Boolean,
    val middleButton: Boolean,
    val eraser: Boolean
)

fun mapPenButtonAction(action: PenButtonAction, pressed: Boolean): TabletButtonFlags {
    if (!pressed) return TabletButtonFlags(false, false, false)
    return when (action) {
        PenButtonAction.RIGHT_CLICK -> TabletButtonFlags(true, false, false)
        PenButtonAction.MIDDLE_CLICK -> TabletButtonFlags(false, true, false)
        PenButtonAction.ERASER -> TabletButtonFlags(false, false, true)
        PenButtonAction.DISABLED -> TabletButtonFlags(false, false, false)
    }
}

object TabletConfig {
    private const val PREFS = "spen_tablet"
    private const val KEY_MONITOR_DETECTED = "monitorDetected"
    private const val KEY_MONITOR_MANUAL = "monitorManual"

    fun getPressureCurve(context: Context): PressureCurveType {
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("pressureCurve", PressureCurveType.LINEAR.name)
        return try { PressureCurveType.valueOf(name!!) } catch (_: Exception) { PressureCurveType.LINEAR }
    }

    fun setPressureCurve(context: Context, value: PressureCurveType) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("pressureCurve", value.name).apply()
    }

    fun getCustomCurvePoints(context: Context): List<Float> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("customCurvePoints", null) ?: return defaultCurvePoints()
        return try {
            val array = JSONArray(json)
            val points = (0 until array.length()).mapNotNull {
                array.getDouble(it).takeIf { value -> value.isFinite() }?.toFloat()
            }.map { it.coerceIn(0f, 1f) }
            if (points.size >= 4 && points.size % 2 == 0) points else defaultCurvePoints()
        } catch (_: Exception) {
            defaultCurvePoints()
        }
    }

    private fun defaultCurvePoints(): List<Float> = listOf(0f, 0f, 0.5f, 0.5f, 1f, 1f)

    fun setCustomCurvePoints(context: Context, value: List<Float>) {
        val array = JSONArray()
        value.take(32).forEach { point ->
            array.put(point.coerceIn(0f, 1f).toDouble())
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("customCurvePoints", array.toString()).apply()
    }

    fun getPressureMin(context: Context): Float =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat("pressureMin", 0f)

    fun setPressureMin(context: Context, value: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("pressureMin", value).apply()
    }

    fun getPressureMax(context: Context): Float =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat("pressureMax", 1f)

    fun setPressureMax(context: Context, value: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("pressureMax", value).apply()
    }

    fun getSmoothing(context: Context): Float =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat("smoothing", 0.3f)

    fun setSmoothing(context: Context, value: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("smoothing", value).apply()
    }

    fun getOrientation(context: Context): OrientationType {
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("orientation", OrientationType.AUTO.name)
        return try { OrientationType.valueOf(name!!) } catch (_: Exception) { OrientationType.AUTO }
    }

    fun setOrientation(context: Context, value: OrientationType) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("orientation", value.name).apply()
    }

    /** Normalizes a display size to landscape order without accepting invalid bounds. */
    fun normalizeScreenResolution(width: Int, height: Int): Pair<Int, Int>? =
        if (width <= 0 || height <= 0) null
        else if (height > width) height to width else width to height

    /**
     * Maps normalized Wacom coordinates into the selected display rotation.
     * Tablet Mode is landscape-first for the normal horizontal PC monitor.
     */
    fun mapCoordinates(
        x: Float,
        y: Float,
        rotation: Int
    ): Pair<Float, Float> = when (rotation) {
        Surface.ROTATION_90 -> y to (1f - x)
        Surface.ROTATION_180 -> (1f - x) to (1f - y)
        Surface.ROTATION_270 -> (1f - y) to x
        else -> x to y
    }

    /** Returns the display rotation without using hidden or API-specific methods. */
    fun displayRotation(context: Context): Int {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: return Surface.ROTATION_0
        return try {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        } catch (_: Exception) {
            Surface.ROTATION_0
        }
    }

    /**
     * Returns the current display bounds before landscape normalization. This
     * distinguishes a naturally-landscape device from a portrait panel that
     * is currently rotated into landscape.
     */
    fun currentDisplayBounds(context: Context): Pair<Int, Int>? {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: return null
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = windowManager.maximumWindowMetrics.bounds
                bounds.width() to bounds.height()
            } else {
                @Suppress("DEPRECATION")
                val metrics = DisplayMetrics().also { windowManager.defaultDisplay.getRealMetrics(it) }
                metrics.widthPixels to metrics.heightPixels
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Finds the quarter-turn that produces ordinary landscape coordinates.
     * A 90/270 display rotation is authoritative; with rotation 0/180 the
     * current bounds tell us whether the panel is naturally landscape.
     */
    fun landscapeRotation(
        displayRotation: Int,
        displayWidth: Int?,
        displayHeight: Int?
    ): Int {
        if (displayRotation == Surface.ROTATION_90 || displayRotation == Surface.ROTATION_270) {
            return displayRotation
        }
        val currentIsLandscape = displayWidth != null && displayHeight != null &&
            displayWidth > 0 && displayHeight > 0 && displayWidth >= displayHeight
        return if (currentIsLandscape) displayRotation else Surface.ROTATION_90
    }

    /**
     * Resolves the axis transform used by an active tablet session. AUTO uses
     * the monitor target orientation supplied by the caller; landscape remains
     * the safe default when no dimensions are available.
     */
    fun coordinateRotation(
        context: Context,
        orientation: OrientationType,
        targetLandscape: Boolean = orientation != OrientationType.PORTRAIT
    ): Int {
        val current = displayRotation(context)
        val bounds = currentDisplayBounds(context)
        val normalLandscape = landscapeRotation(current, bounds?.first, bounds?.second)
        val wantsLandscape = when (orientation) {
            OrientationType.PORTRAIT -> false
            OrientationType.LANDSCAPE, OrientationType.LANDSCAPE_INV -> true
            OrientationType.AUTO -> targetLandscape
        }
        if (!wantsLandscape) {
            // Portrait is the digitizer's natural orientation on many phones.
            return if (current == Surface.ROTATION_180) Surface.ROTATION_180 else Surface.ROTATION_0
        }
        if (orientation == OrientationType.LANDSCAPE_INV) {
            return when (normalLandscape) {
                Surface.ROTATION_90 -> Surface.ROTATION_270
                Surface.ROTATION_270 -> Surface.ROTATION_90
                Surface.ROTATION_180 -> Surface.ROTATION_0
                else -> Surface.ROTATION_180
            }
        }
        return normalLandscape
    }

    /** Returns the physical display bounds in landscape order. */
    fun detectScreenResolution(context: Context): Pair<Int, Int>? =
        currentDisplayBounds(context)?.let { normalizeScreenResolution(it.first, it.second) }

    /** Detects and persists the current display size unless the user overrides it. */
    fun detectAndStoreScreenResolution(context: Context): Pair<Int, Int>? =
        detectScreenResolution(context)?.also { (width, height) ->
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt("monitorWidth", width)
                .putInt("monitorHeight", height)
                .putBoolean(KEY_MONITOR_DETECTED, true)
                .putBoolean(KEY_MONITOR_MANUAL, false)
                .apply()
        }

    fun isMonitorAutoDetected(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_MONITOR_DETECTED, false) &&
            !prefs.getBoolean(KEY_MONITOR_MANUAL, false)
    }

    fun isMonitorManuallyConfigured(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_MONITOR_MANUAL, false)

    /** Marks values entered in Tablet Settings as an intentional user override. */
    fun markMonitorConfigured(context: Context, manual: Boolean = true) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_MONITOR_DETECTED, true)
            .putBoolean(KEY_MONITOR_MANUAL, manual)
            .apply()
    }

    fun getMappingMode(context: Context): MappingMode {
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("mappingMode", MappingMode.FULL_SCREEN.name)
        return try { MappingMode.valueOf(name!!) } catch (_: Exception) { MappingMode.FULL_SCREEN }
    }

    fun setMappingMode(context: Context, value: MappingMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("mappingMode", value.name).apply()
    }

    fun getCustomArea(context: Context): FloatArray {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val left = prefs.getFloat("customAreaLeft", 0f).coerceIn(0f, 1f)
        val top = prefs.getFloat("customAreaTop", 0f).coerceIn(0f, 1f)
        val right = prefs.getFloat("customAreaRight", 1f).coerceIn(0f, 1f)
        val bottom = prefs.getFloat("customAreaBottom", 1f).coerceIn(0f, 1f)
        return floatArrayOf(
            minOf(left, right),
            minOf(top, bottom),
            maxOf(left, right),
            maxOf(top, bottom)
        )
    }

    fun setCustomArea(context: Context, left: Float, top: Float, right: Float, bottom: Float) {
        val safeLeft = left.coerceIn(0f, 1f)
        val safeTop = top.coerceIn(0f, 1f)
        val safeRight = right.coerceIn(0f, 1f)
        val safeBottom = bottom.coerceIn(0f, 1f)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("customAreaLeft", minOf(safeLeft, safeRight))
            .putFloat("customAreaTop", minOf(safeTop, safeBottom))
            .putFloat("customAreaRight", maxOf(safeLeft, safeRight))
            .putFloat("customAreaBottom", maxOf(safeTop, safeBottom))
            .apply()
    }

    fun getPenButtonAction(context: Context): PenButtonAction {
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("penButtonAction", PenButtonAction.RIGHT_CLICK.name)
        return try { PenButtonAction.valueOf(name!!) } catch (_: Exception) { PenButtonAction.RIGHT_CLICK }
    }

    fun setPenButtonAction(context: Context, value: PenButtonAction) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("penButtonAction", value.name).apply()
    }

    fun getInvertX(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("invertX", false)

    fun setInvertX(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("invertX", value).apply()
    }

    fun getInvertY(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("invertY", false)

    fun setInvertY(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("invertY", value).apply()
    }

    fun getAspectRatioLock(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("aspectRatioLock", true)

    fun setAspectRatioLock(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("aspectRatioLock", value).apply()
    }

    fun getMonitorWidth(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("monitorWidth", 1920)

    fun setMonitorWidth(context: Context, value: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("monitorWidth", value.coerceAtLeast(1)).apply()
    }

    fun getMonitorHeight(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("monitorHeight", 1080)

    fun setMonitorHeight(context: Context, value: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("monitorHeight", value.coerceAtLeast(1)).apply()
    }

    fun getSendRateHz(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("sendRateHz", 133)

    fun setSendRateHz(context: Context, value: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("sendRateHz", value.coerceIn(30, 200)).apply()
    }

    fun getHapticFeedback(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean("hapticFeedback", true)

    fun setHapticFeedback(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("hapticFeedback", value).apply()
    }

    fun getShowGrid(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("showGrid", true)

    fun setShowGrid(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("showGrid", value).apply()
    }

    fun getAutoRestoreUsb(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean("autoRestoreUsb", true)

    fun setAutoRestoreUsb(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("autoRestoreUsb", value).apply()
    }
}
