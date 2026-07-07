package com.denis.spenfix

import android.content.Context
import org.json.JSONArray

enum class PressureCurveType { LINEAR, SOFT, FIRM, S_CURVE, CUSTOM }
enum class OrientationType { AUTO, PORTRAIT, LANDSCAPE, LANDSCAPE_INV }
enum class MappingMode { FULL_SCREEN, CUSTOM_AREA }
enum class PenButtonAction { RIGHT_CLICK, MIDDLE_CLICK, ERASER, DISABLED }

object TabletConfig {
    private const val PREFS = "spen_tablet"

    fun getPressureCurve(context: Context): PressureCurveType {
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("pressureCurve", PressureCurveType.LINEAR.name)
        return try { PressureCurveType.valueOf(name!!) } catch (e: Exception) { PressureCurveType.LINEAR }
    }

    fun setPressureCurve(context: Context, value: PressureCurveType) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("pressureCurve", value.name).apply()
    }

    fun getCustomCurvePoints(context: Context): List<Float> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("customCurvePoints", null) ?: return listOf(0f, 0f, 0.5f, 0.5f, 1f, 1f)
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { array.getDouble(it).toFloat() }
        } catch (e: Exception) {
            listOf(0f, 0f, 0.5f, 0.5f, 1f, 1f)
        }
    }

    fun setCustomCurvePoints(context: Context, value: List<Float>) {
        val array = JSONArray()
        value.forEach { array.put(it.toDouble()) }
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
        return try { OrientationType.valueOf(name!!) } catch (e: Exception) { OrientationType.AUTO }
    }

    fun setOrientation(context: Context, value: OrientationType) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("orientation", value.name).apply()
    }

    fun getMappingMode(context: Context): MappingMode {
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("mappingMode", MappingMode.FULL_SCREEN.name)
        return try { MappingMode.valueOf(name!!) } catch (e: Exception) { MappingMode.FULL_SCREEN }
    }

    fun setMappingMode(context: Context, value: MappingMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("mappingMode", value.name).apply()
    }

    fun getCustomArea(context: Context): FloatArray {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return floatArrayOf(
            prefs.getFloat("customAreaLeft", 0f),
            prefs.getFloat("customAreaTop", 0f),
            prefs.getFloat("customAreaRight", 1f),
            prefs.getFloat("customAreaBottom", 1f)
        )
    }

    fun setCustomArea(context: Context, left: Float, top: Float, right: Float, bottom: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("customAreaLeft", left)
            .putFloat("customAreaTop", top)
            .putFloat("customAreaRight", right)
            .putFloat("customAreaBottom", bottom)
            .apply()
    }

    fun getPenButtonAction(context: Context): PenButtonAction {
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("penButtonAction", PenButtonAction.RIGHT_CLICK.name)
        return try { PenButtonAction.valueOf(name!!) } catch (e: Exception) { PenButtonAction.RIGHT_CLICK }
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
            .putInt("monitorWidth", value).apply()
    }

    fun getMonitorHeight(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("monitorHeight", 1080)

    fun setMonitorHeight(context: Context, value: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("monitorHeight", value).apply()
    }

    fun getSendRateHz(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("sendRateHz", 133)

    fun setSendRateHz(context: Context, value: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("sendRateHz", value).apply()
    }

    fun getHapticFeedback(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("hapticFeedback", true)

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
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("autoRestoreUsb", true)

    fun setAutoRestoreUsb(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("autoRestoreUsb", value).apply()
    }
}
