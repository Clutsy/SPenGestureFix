package com.denis.spenfix

/**
 * A concrete action: the type (see ActionType), the label shown in the
 * app and in the wheel, and an optional target — package name for
 * LAUNCH_APP/PEN_WINDOW, shell command for CUSTOM_SHELL, otherwise empty.
 */
data class PenAction(
    val type: ActionType,
    val label: String,
    val target: String = ""
)
