package com.spengesturefix

import android.content.Context
import androidx.core.content.edit

/** Small app-level preferences shared by Compose screens and the service. */
object AppSettings {
    private const val PREFS = "app_settings"
    private const val KEY_AMOLED = "amoled_black"
    private const val KEY_AUTO_START_PEN = "auto_start_pen"
    private const val KEY_BATTERY_SAVER = "battery_saver"
    private const val KEY_LANGUAGE = "language"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isAmoled(context: Context): Boolean = prefs(context).getBoolean(KEY_AMOLED, true)

    fun setAmoled(context: Context, value: Boolean) {
        prefs(context).edit { putBoolean(KEY_AMOLED, value) }
    }

    fun isAutoStartOnPen(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_START_PEN, true)

    fun setAutoStartOnPen(context: Context, value: Boolean) {
        prefs(context).edit { putBoolean(KEY_AUTO_START_PEN, value) }
    }

    /**
     * Parks the digitizer reader after five idle seconds with the pen in its
     * slot; extraction wakes everything instantly. On by default.
     */
    fun isBatterySaver(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BATTERY_SAVER, true)

    fun setBatterySaver(context: Context, value: Boolean) {
        prefs(context).edit { putBoolean(KEY_BATTERY_SAVER, value) }
    }

    fun language(context: Context): String? = when (val value = prefs(context).getString(KEY_LANGUAGE, null)) {
        // Migrate the legacy resource-qualifier spellings to BCP-47 tags.
        "zh-rCN" -> "zh-CN"
        "in" -> "id"
        else -> value
    }

    fun setLanguage(context: Context, value: String?) {
        prefs(context).edit {
            if (value.isNullOrBlank()) remove(KEY_LANGUAGE) else putString(KEY_LANGUAGE, value)
        }
    }
}
