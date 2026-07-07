package com.denis.spenfix

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import androidx.appcompat.app.AppCompatActivity
import com.denis.spenfix.databinding.ActivityTabletSettingsBinding

class TabletSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTabletSettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTabletSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadSettings()
        setupListeners()
    }

    private fun loadSettings() {
        val context = this

        // 1. Pressure Curve
        val curve = TabletConfig.getPressureCurve(context)
        binding.curveView.setCurveType(curve)
        val customPoints = TabletConfig.getCustomCurvePoints(context)
        binding.curveView.setCustomPoints(customPoints)

        when (curve) {
            PressureCurveType.LINEAR -> binding.chipLinear.isChecked = true
            PressureCurveType.SOFT -> binding.chipSoft.isChecked = true
            PressureCurveType.FIRM -> binding.chipFirm.isChecked = true
            PressureCurveType.S_CURVE -> binding.chipSCurve.isChecked = true
            PressureCurveType.CUSTOM -> binding.chipCustom.isChecked = true
        }

        binding.sliderPressureMin.value = TabletConfig.getPressureMin(context)
        binding.sliderPressureMax.value = TabletConfig.getPressureMax(context)

        // 2. Mapping
        val orient = TabletConfig.getOrientation(context)
        when (orient) {
            OrientationType.AUTO -> binding.chipOrientAuto.isChecked = true
            OrientationType.PORTRAIT -> binding.chipOrientPort.isChecked = true
            OrientationType.LANDSCAPE -> binding.chipOrientLand.isChecked = true
            OrientationType.LANDSCAPE_INV -> binding.chipOrientLandInv.isChecked = true
        }

        binding.switchInvertX.isChecked = TabletConfig.getInvertX(context)
        binding.switchInvertY.isChecked = TabletConfig.getInvertY(context)
        binding.switchAspectRatio.isChecked = TabletConfig.getAspectRatioLock(context)
        binding.etMonitorW.setText(TabletConfig.getMonitorWidth(context).toString())
        binding.etMonitorH.setText(TabletConfig.getMonitorHeight(context).toString())

        // 3. Performance
        binding.sliderSendRate.value = TabletConfig.getSendRateHz(context).toFloat()
        binding.sliderSmoothing.value = TabletConfig.getSmoothing(context)

        // 4. Pen Button
        val btnAction = TabletConfig.getPenButtonAction(context)
        when (btnAction) {
            PenButtonAction.RIGHT_CLICK -> binding.chipBtnRight.isChecked = true
            PenButtonAction.MIDDLE_CLICK -> binding.chipBtnMiddle.isChecked = true
            PenButtonAction.ERASER -> binding.chipBtnEraser.isChecked = true
            PenButtonAction.DISABLED -> binding.chipBtnDisabled.isChecked = true
        }

        // 5. Options
        binding.switchHaptic.isChecked = TabletConfig.getHapticFeedback(context)
        binding.switchGrid.isChecked = TabletConfig.getShowGrid(context)
        binding.switchAutoRestore.isChecked = TabletConfig.getAutoRestoreUsb(context)
    }

    private fun setupListeners() {
        val context = this

        // Curve Selection
        binding.chipGroupCurve.setOnCheckedChangeListener { _, checkedId ->
            val selectedCurve = when (checkedId) {
                R.id.chipLinear -> PressureCurveType.LINEAR
                R.id.chipSoft -> PressureCurveType.SOFT
                R.id.chipFirm -> PressureCurveType.FIRM
                R.id.chipSCurve -> PressureCurveType.S_CURVE
                R.id.chipCustom -> PressureCurveType.CUSTOM
                else -> PressureCurveType.LINEAR
            }
            TabletConfig.setPressureCurve(context, selectedCurve)
            binding.curveView.setCurveType(selectedCurve)
        }

        binding.curveView.setOnCurveChangedListener { points ->
            TabletConfig.setCustomCurvePoints(context, points)
        }

        binding.sliderPressureMin.addOnChangeListener { _, value, _ ->
            TabletConfig.setPressureMin(context, value)
        }

        binding.sliderPressureMax.addOnChangeListener { _, value, _ ->
            TabletConfig.setPressureMax(context, value)
        }

        // Orientation Selection
        binding.chipGroupOrientation.setOnCheckedChangeListener { _, checkedId ->
            val selectedOrient = when (checkedId) {
                R.id.chipOrientAuto -> OrientationType.AUTO
                R.id.chipOrientPort -> OrientationType.PORTRAIT
                R.id.chipOrientLand -> OrientationType.LANDSCAPE
                R.id.chipOrientLandInv -> OrientationType.LANDSCAPE_INV
                else -> OrientationType.AUTO
            }
            TabletConfig.setOrientation(context, selectedOrient)
        }

        binding.switchInvertX.setOnCheckedChangeListener { _, isChecked ->
            TabletConfig.setInvertX(context, isChecked)
        }

        binding.switchInvertY.setOnCheckedChangeListener { _, isChecked ->
            TabletConfig.setInvertY(context, isChecked)
        }

        binding.switchAspectRatio.setOnCheckedChangeListener { _, isChecked ->
            TabletConfig.setAspectRatioLock(context, isChecked)
        }

        // Monitor dimensions
        binding.etMonitorW.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val value = s?.toString()?.toIntOrNull() ?: 1920
                TabletConfig.setMonitorWidth(context, value)
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        binding.etMonitorH.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val value = s?.toString()?.toIntOrNull() ?: 1080
                TabletConfig.setMonitorHeight(context, value)
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // Performance
        binding.sliderSendRate.addOnChangeListener { _, value, _ ->
            TabletConfig.setSendRateHz(context, value.toInt())
        }

        binding.sliderSmoothing.addOnChangeListener { _, value, _ ->
            TabletConfig.setSmoothing(context, value)
        }

        // Pen Button Selection
        binding.chipGroupPenButton.setOnCheckedChangeListener { _, checkedId ->
            val selectedBtn = when (checkedId) {
                R.id.chipBtnRight -> PenButtonAction.RIGHT_CLICK
                R.id.chipBtnMiddle -> PenButtonAction.MIDDLE_CLICK
                R.id.chipBtnEraser -> PenButtonAction.ERASER
                R.id.chipBtnDisabled -> PenButtonAction.DISABLED
                else -> PenButtonAction.RIGHT_CLICK
            }
            TabletConfig.setPenButtonAction(context, selectedBtn)
        }

        // Switches
        binding.switchHaptic.setOnCheckedChangeListener { _, isChecked ->
            TabletConfig.setHapticFeedback(context, isChecked)
        }

        binding.switchGrid.setOnCheckedChangeListener { _, isChecked ->
            TabletConfig.setShowGrid(context, isChecked)
        }

        binding.switchAutoRestore.setOnCheckedChangeListener { _, isChecked ->
            TabletConfig.setAutoRestoreUsb(context, isChecked)
        }
    }
}
