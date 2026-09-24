package com.spengesturefix

import android.content.Context

/**
 * Visual style of the Air Command wheel.
 *
 * [MODERN] is the app's own even quarter-arc fan with labeled discs.
 * [RETRO] reproduces the classic SpenCommand wheel extracted by reverse
 * engineering `SpenCommand_1238_pp_BETA1.apk`: a black translucent fan
 * anchored in the lower-right corner, small icon-only discs laid out along
 * the original measured geometry (radii 40..118dp, angles -51..+44 degrees
 * from vertical), with the selected action's name shown in a readout near
 * the anchor instead of labels under each icon.
 */
enum class WheelStyle(val labelResId: Int) {
    MODERN(R.string.wheel_style_modern),
    RETRO(R.string.wheel_style_retro);

    companion object {
        private const val PREFS = "spen_wheel"
        private const val KEY_STYLE = "wheel_style"

        fun load(context: Context): WheelStyle =
            try {
                valueOf(
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .getString(KEY_STYLE, MODERN.name) ?: MODERN.name
                )
            } catch (_: Exception) {
                MODERN
            }

        fun save(context: Context, style: WheelStyle) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_STYLE, style.name)
                .apply()
        }
    }

    /** Localized display name comes straight from [labelResId]. */
}
