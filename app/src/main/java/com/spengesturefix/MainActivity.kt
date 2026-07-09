package com.denis.spenfix

import android.app.LocaleManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import com.denis.spenfix.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val pickBackgroundLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
            WheelConfig.saveBackgroundUri(this, uri)
            refreshWheelBackground()
            Toast.makeText(this, getString(R.string.wheel_bg_updated), Toast.LENGTH_SHORT).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyAmoledTheme()

        binding.btnCheckRoot.setOnClickListener { checkRoot() }
        binding.btnOverlayPermission.setOnClickListener { requestOverlayPermission() }
        binding.btnStartService.setOnClickListener { requestPermissionsAndStart() }
        binding.btnStopService.setOnClickListener {
            stopService(Intent(this, SPenGestureService::class.java))
            Toast.makeText(this, getString(R.string.toast_service_stopped), Toast.LENGTH_SHORT).show()
        }
        binding.btnPickBackground.setOnClickListener { pickBackgroundLauncher.launch(arrayOf("image/*")) }
        binding.btnClearBackground.setOnClickListener {
            WheelConfig.clearBackground(this)
            refreshWheelBackground()
        }
        binding.btnViewNotes.setOnClickListener { startActivity(Intent(this, NotesListActivity::class.java)) }
        binding.btnStartTablet.setOnClickListener { startActivity(Intent(this, TabletModeActivity::class.java)) }
        binding.btnTabletSettings.setOnClickListener { startActivity(Intent(this, TabletSettingsActivity::class.java)) }

        // Language row
        binding.rowLanguage.setOnClickListener { showLanguagePicker() }
        updateLanguageLabel()

        // AMOLED toggle
        binding.switchAmoled.isChecked = AppSettings.isAmoled(this)
        binding.switchAmoled.setOnCheckedChangeListener { _, isChecked ->
            AppSettings.setAmoled(this, isChecked)
            recreate()
        }

        // Auto-start on pen extraction
        binding.switchAutoStartPen.isChecked = AppSettings.isAutoStartOnPen(this)
        binding.switchAutoStartPen.setOnCheckedChangeListener { _, isChecked ->
            AppSettings.setAutoStartOnPen(this, isChecked)
        }

        buildGestureRows()
        buildWheelSlotRows()
        refreshWheelBackground()

        // Request permissions on first launch / when missing
        ensurePermissionsOnStartup()
    }

    override fun onResume() {
        super.onResume()
        buildGestureRows()
        buildWheelSlotRows()
        refreshWheelBackground()
    }

    // --- Theme / AMOLED ---

    private fun applyAmoledTheme() {
        val amoled = AppSettings.isAmoled(this)
        window.decorView.setBackgroundColor(if (amoled) Color.BLACK else Color.parseColor("#0A0A0B"))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            window.navigationBarColor = Color.BLACK
            window.statusBarColor = Color.TRANSPARENT
        }
    }

    // --- Language ---

    private fun currentLanguageCode(): String? =
        getSharedPreferences("app_settings", Context.MODE_PRIVATE).getString("language", null)

    private fun updateLanguageLabel() {
        val code = currentLanguageCode()
        val label = when (code) {
            "en" -> getString(R.string.settings_language_en)
            "it" -> getString(R.string.settings_language_it)
            else -> getString(R.string.settings_language_system)
        }
        binding.tvLanguageValue.text = label
    }

    private fun showLanguagePicker() {
        val labels = arrayOf(
            getString(R.string.settings_language_system),
            getString(R.string.settings_language_en),
            getString(R.string.settings_language_it)
        )
        val codes = arrayOf(null, "en", "it")
        val checked = when (currentLanguageCode()) {
            "en" -> 1
            "it" -> 2
            else -> 0
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_language)
            .setSingleChoiceItems(labels, checked) { dlg, which ->
                val code = codes[which]
                setLanguage(code)
                updateLanguageLabel()
                Toast.makeText(this, R.string.toast_language_changed, Toast.LENGTH_SHORT).show()
                dlg.dismiss()
            }
            .setNegativeButton(R.string.dialog_cancel) { dlg, _ -> dlg.dismiss() }
            .show()
    }

    private fun setLanguage(code: String?) {
        getSharedPreferences("app_settings", Context.MODE_PRIVATE).edit {
            if (code == null) remove("language") else putString("language", code)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            val locales = if (code == null) LocaleList.getEmptyLocaleList()
            else LocaleList.forLanguageTags(code)
            getSystemService(LocaleManager::class.java).applicationLocales = locales
        }
    }

    // --- Gesture rows ---

    private fun buildGestureRows() {
        binding.gestureContainer.removeAllViews()
        GestureKind.values().forEach { gesture ->
            val action = GestureBindings.load(this, gesture)
            binding.gestureContainer.addView(
                createActionRow(labelFor(gesture), action.label) {
                    ActionPickerDialog.show(this) { picked ->
                        GestureBindings.save(this, gesture, picked)
                        buildGestureRows()
                    }
                }
            )
        }
    }

    private fun labelFor(gesture: GestureKind): String = when (gesture) {
        GestureKind.CLICK -> getString(R.string.gesture_click)
        GestureKind.DOUBLE_CLICK -> getString(R.string.gesture_double_click)
        GestureKind.LONG_PRESS -> getString(R.string.gesture_long_press)
    }

    // --- Wheel slot rows ---

    private fun buildWheelSlotRows() {
        binding.wheelSlotsContainer.removeAllViews()
        val slots = WheelConfig.loadSlots(this)
        for (i in 0 until WheelConfig.SLOT_COUNT) {
            val action = slots.getOrNull(i) ?: PenAction(ActionType.NONE, ActionType.NONE.label(this))
            binding.wheelSlotsContainer.addView(
                createActionRow(getString(R.string.wheel_slot_label, i + 1), action.label) {
                    ActionPickerDialog.show(this) { picked ->
                        WheelConfig.saveSlot(this, i, picked)
                        buildWheelSlotRows()
                    }
                }
            )
        }
    }

    private fun refreshWheelBackground() {
        val bitmap = WheelConfig.loadBackgroundBitmap(this)
        if (bitmap != null) {
            binding.imgWheelBackground.setImageBitmap(bitmap)
        } else {
            binding.imgWheelBackground.setImageDrawable(null)
            binding.imgWheelBackground.setBackgroundColor(Color.parseColor("#16161A"))
        }
    }

    /**
     * Clean clickable row: title + current value with subtle divider.
     * Designed to match Material 3 list items.
     */
    private fun createActionRow(title: String, currentLabel: String, onClick: () -> Unit): View {
        val density = resources.displayMetrics.density
        val padV = (10 * density).toInt()
        val padH = (4 * density).toInt()

        // Container with vertical content + bottom divider
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            setBackgroundResource(android.R.drawable.list_selector_background)
        }

        // Top horizontal row
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(padH, padV, padH, padV)
        }

        row.addView(TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(Color.parseColor("#E7E7EA"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })

        row.addView(TextView(this).apply {
            text = currentLabel
            textSize = 13f
            setTextColor(Color.parseColor("#8E8E95"))
            gravity = Gravity.END
            maxLines = 1
            maxEms = 12
            ellipsize = android.text.TextUtils.TruncateAt.END
        })

        container.addView(row)

        // Subtle divider line
        val divider = View(this).apply {
            setBackgroundColor(Color.parseColor("#1A1A1E"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                setMargins(padH, 0, padH, 0)
            }
        }
        container.addView(divider)

        container.setOnClickListener { onClick() }
        return container
    }

    // --- Permissions ---

    private fun ensurePermissionsOnStartup() {
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasNotif = if (Build.VERSION.SDK_INT >= 33)
            (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED)
        else true

        if (!hasOverlay || !hasNotif) {
            showPermissionsDialog(hasOverlay, hasNotif)
        }
    }

    private fun showPermissionsDialog(hasOverlay: Boolean, hasNotif: Boolean) {
        val msg = StringBuilder()
        if (!hasOverlay) msg.append("- ").append(getString(R.string.perm_overlay)).append("\n\n")
        if (!hasNotif) msg.append("- ").append(getString(R.string.perm_notification)).append("\n\n")
        msg.append(getString(R.string.perm_root))

        AlertDialog.Builder(this)
            .setTitle(R.string.perm_title)
            .setMessage(msg.toString())
            .setPositiveButton(R.string.perm_grant_all) { dlg, _ ->
                if (!hasOverlay) requestOverlayPermission()
                if (!hasNotif && Build.VERSION.SDK_INT >= 33) {
                    notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
                dlg.dismiss()
            }
            .setNegativeButton(R.string.perm_continue) { dlg, _ -> dlg.dismiss() }
            .setCancelable(false)
            .show()
    }

    // --- Root + overlay + service ---

    private fun checkRoot() {
        Thread {
            val hasRoot = try {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
                val output = process.inputStream.bufferedReader().readText()
                process.waitFor() == 0 && output.contains("uid=0")
            } catch (e: Exception) {
                false
            }
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (hasRoot) getString(R.string.toast_root_ok) else getString(R.string.toast_root_fail),
                    Toast.LENGTH_LONG
                ).show()
            }
        }.start()
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } else {
            Toast.makeText(this, getString(R.string.toast_overlay_granted), Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestPermissionsAndStart() {
        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        val serviceIntent = Intent(this, SPenGestureService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        Toast.makeText(this, getString(R.string.toast_service_started), Toast.LENGTH_SHORT).show()
    }
}

/** Small helper for app-level persistent settings: AMOLED mode, language, auto-start on pen extraction. */
object AppSettings {
    private const val PREFS = "app_settings"
    private const val KEY_AMOLED = "amoled_black"
    private const val KEY_AUTO_START_PEN = "auto_start_pen"

    fun isAmoled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AMOLED, true)

    fun setAmoled(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_AMOLED, value).apply()
    }

    fun isAutoStartOnPen(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTO_START_PEN, true)

    fun setAutoStartOnPen(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_AUTO_START_PEN, value).apply()
    }
}
