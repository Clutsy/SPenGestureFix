package com.denis.spenfix

/**
 * Un'azione concreta: il tipo (vedi ActionType), l'etichetta mostrata
 * nell'app e nella ruota, e un target opzionale — package name per
 * LAUNCH_APP/PEN_WINDOW, comando shell per CUSTOM_SHELL, altrimenti vuoto.
 */
data class PenAction(
    val type: ActionType,
    val label: String,
    val target: String = ""
)
