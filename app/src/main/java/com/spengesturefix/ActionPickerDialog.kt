package com.denis.spenfix

import android.app.AlertDialog
import android.content.Context
import android.widget.EditText

object ActionPickerDialog {

    fun show(context: Context, onPicked: (PenAction) -> Unit) {
        val types = ActionType.values().toList()
        val labels = types.map { it.label(context) }.toTypedArray()

        AlertDialog.Builder(context)
            .setTitle(R.string.dialog_choose_action)
            .setItems(labels) { _, which ->
                val type = types[which]
                when {
                    type.needsAppTarget -> AppPicker.pick(context) { entry ->
                        onPicked(PenAction(type, entry.label, entry.packageName))
                    }
                    type.needsTextTarget -> showTextInput(context, type, onPicked)
                    else -> onPicked(PenAction(type, type.label(context)))
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun showTextInput(context: Context, type: ActionType, onPicked: (PenAction) -> Unit) {
        val input = EditText(context).apply {
            hint = context.getString(R.string.dialog_shell_hint)
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.dialog_shell_title)
            .setView(input)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                val command = input.text.toString()
                if (command.isNotBlank()) {
                    onPicked(PenAction(type, context.getString(R.string.command_prefix, command.take(24)), command))
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }
}
