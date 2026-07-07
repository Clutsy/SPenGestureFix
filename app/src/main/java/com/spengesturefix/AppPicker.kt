package com.denis.spenfix

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView

object AppPicker {

    data class AppEntry(val label: String, val packageName: String)

    fun pick(context: Context, onPicked: (AppEntry) -> Unit) {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val allEntries = pm.queryIntentActivities(mainIntent, 0)
            .map { AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .sortedBy { it.label.lowercase() }

        var visibleEntries = allEntries

        val searchBox = EditText(context).apply { hint = context.getString(R.string.dialog_search_app) }
        val listView = ListView(context)
        val adapter = ArrayAdapter(context, android.R.layout.simple_list_item_1, visibleEntries.map { it.label })
        listView.adapter = adapter

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 0)
            addView(searchBox)
            addView(listView)
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.dialog_choose_app)
            .setView(container)
            .setNegativeButton(R.string.dialog_cancel, null)
            .create()

        listView.setOnItemClickListener { _, _, position, _ ->
            val entry = visibleEntries.getOrNull(position) ?: return@setOnItemClickListener
            onPicked(entry)
            dialog.dismiss()
        }

        searchBox.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.lowercase().orEmpty()
                visibleEntries = allEntries.filter { it.label.lowercase().contains(query) }
                adapter.clear()
                adapter.addAll(visibleEntries.map { it.label })
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        dialog.show()
    }
}
