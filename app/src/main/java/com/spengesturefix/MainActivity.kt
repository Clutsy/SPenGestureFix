package com.denis.spenfix

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
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

        binding.btnCheckRoot.setOnClickListener { checkRoot() }
        binding.btnOverlayPermission.setOnClickListener { requestOverlayPermission() }
        binding.btnStartService.setOnClickListener { requestPermissionsAndStart() }
        binding.btnStopService.setOnClickListener { stopService(Intent(this, SPenGestureService::class.java)) }
        binding.btnPickBackground.setOnClickListener { pickBackgroundLauncher.launch(arrayOf("image/*")) }
        binding.btnClearBackground.setOnClickListener {
            WheelConfig.clearBackground(this)
            refreshWheelBackground()
        }
        binding.btnViewNotes.setOnClickListener { startActivity(Intent(this, NotesListActivity::class.java)) }

        // Wacom Tablet Mode click listeners
        binding.btnStartTablet.setOnClickListener {
            startActivity(Intent(this, TabletModeActivity::class.java))
        }
        binding.btnTabletSettings.setOnClickListener {
            startActivity(Intent(this, TabletSettingsActivity::class.java))
        }

        buildGestureRows()
        buildWheelSlotRows()
        refreshWheelBackground()
    }

    override fun onResume() {
        super.onResume()
        buildGestureRows()
        buildWheelSlotRows()
        refreshWheelBackground()
    }

    // --- Righe per i 3 gesti del tasto ---

    private fun buildGestureRows() {
        binding.gestureContainer.removeAllViews()
        GestureKind.values().forEach { gesture ->
            val action = GestureBindings.load(this, gesture)
            binding.gestureContainer.addView(createActionRow(labelFor(gesture), action.label) {
                ActionPickerDialog.show(this) { picked ->
                    GestureBindings.save(this, gesture, picked)
                    buildGestureRows()
                }
            })
        }
    }

    private fun labelFor(gesture: GestureKind): String = when (gesture) {
        GestureKind.CLICK -> getString(R.string.gesture_click)
        GestureKind.DOUBLE_CLICK -> getString(R.string.gesture_double_click)
        GestureKind.LONG_PRESS -> getString(R.string.gesture_long_press)
    }

    // --- Righe per gli spicchi della ruota ---

    private fun buildWheelSlotRows() {
        binding.wheelSlotsContainer.removeAllViews()
        val slots = WheelConfig.loadSlots(this)
        for (i in 0 until WheelConfig.SLOT_COUNT) {
            val action = slots.getOrNull(i) ?: PenAction(ActionType.NONE, ActionType.NONE.label(this))
            binding.wheelSlotsContainer.addView(createActionRow(getString(R.string.wheel_slot_label, i + 1), action.label) {
                ActionPickerDialog.show(this) { picked ->
                    WheelConfig.saveSlot(this, i, picked)
                    buildWheelSlotRows()
                }
            })
        }
    }

    private fun refreshWheelBackground() {
        val bitmap = WheelConfig.loadBackgroundBitmap(this)
        if (bitmap != null) {
            binding.imgWheelBackground.setImageBitmap(bitmap)
        } else {
            binding.imgWheelBackground.setImageDrawable(null)
            binding.imgWheelBackground.setBackgroundColor(Color.parseColor("#121212"))
        }
    }

    /** Riga cliccabile "titolo — azione attuale", riusata per gesti e spicchi. */
    private fun createActionRow(title: String, currentLabel: String, onClick: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 24, 0, 24)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }

            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 14f
                setTextColor(Color.parseColor("#EAEAEA"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@MainActivity).apply {
                text = currentLabel
                textSize = 13f
                alpha = 0.7f
                setTextColor(Color.parseColor("#888888"))
                gravity = Gravity.END
            })
        }
    }

    // --- Permessi e servizio ---

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
