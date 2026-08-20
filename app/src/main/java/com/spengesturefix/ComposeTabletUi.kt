@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.denis.spenfix

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun TabletModeComposeScreen(
    frame: TabletFrame,
    running: Boolean,
    status: String,
    ip: String?,
    showGrid: Boolean,
    onToggle: () -> Unit,
    onSettings: () -> Unit,
    onExit: () -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        TabletPreviewCanvas(frame, showGrid, Modifier.fillMaxSize())
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xCC0B0B0E)),
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(12.dp)
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (running) stringResource(R.string.tablet_status_active) else status,
                    modifier = Modifier.weight(1f),
                    color = if (running) Color(0xFF73D7A5) else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "P ${(frame.pressure * 100).toInt()}%",
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (!ip.isNullOrBlank() && !running) {
                Text(
                    text = stringResource(R.string.tablet_connect_to, ip, TabletNetworkServer.PORT),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
                )
            }
        }
        Button(
            onClick = onToggle,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(20.dp)
        ) {
            Text(if (running) stringResource(R.string.tablet_stop) else stringResource(R.string.btn_start_tablet))
        }
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = onExit) { Text(stringResource(R.string.dialog_cancel)) }
            OutlinedButton(onClick = onSettings) { Text(stringResource(R.string.btn_tablet_settings)) }
        }
    }
}

@Composable
fun TabletSettingsComposeScreen(
    curve: PressureCurveType,
    customPoints: List<Float>,
    pressureMin: Float,
    pressureMax: Float,
    orientation: OrientationType,
    invertX: Boolean,
    invertY: Boolean,
    aspectLock: Boolean,
    monitorWidth: Int,
    monitorHeight: Int,
    sendRate: Int,
    smoothing: Float,
    buttonAction: PenButtonAction,
    haptic: Boolean,
    showGrid: Boolean,
    autoRestore: Boolean,
    onCurveChanged: (PressureCurveType) -> Unit,
    onCustomPointsChanged: (List<Float>) -> Unit,
    onPressureMinChanged: (Float) -> Unit,
    onPressureMaxChanged: (Float) -> Unit,
    onOrientationChanged: (OrientationType) -> Unit,
    onInvertXChanged: (Boolean) -> Unit,
    onInvertYChanged: (Boolean) -> Unit,
    onAspectLockChanged: (Boolean) -> Unit,
    onMonitorWidthChanged: (Int) -> Unit,
    onMonitorHeightChanged: (Int) -> Unit,
    onSendRateChanged: (Int) -> Unit,
    onSmoothingChanged: (Float) -> Unit,
    onButtonActionChanged: (PenButtonAction) -> Unit,
    onHapticChanged: (Boolean) -> Unit,
    onShowGridChanged: (Boolean) -> Unit,
    onAutoRestoreChanged: (Boolean) -> Unit,
    onClose: () -> Unit
) {
    var selectedCurve by remember { mutableStateOf(curve) }
    var selectedCustomPoints by remember { mutableStateOf(customPoints) }
    var selectedPressureMin by remember { mutableStateOf(pressureMin) }
    var selectedPressureMax by remember { mutableStateOf(pressureMax) }
    var selectedOrientation by remember { mutableStateOf(orientation) }
    var selectedInvertX by remember { mutableStateOf(invertX) }
    var selectedInvertY by remember { mutableStateOf(invertY) }
    var selectedAspectLock by remember { mutableStateOf(aspectLock) }
    var selectedSendRate by remember { mutableStateOf(sendRate) }
    var selectedSmoothing by remember { mutableStateOf(smoothing) }
    var selectedButtonAction by remember { mutableStateOf(buttonAction) }
    var selectedHaptic by remember { mutableStateOf(haptic) }
    var selectedShowGrid by remember { mutableStateOf(showGrid) }
    var selectedAutoRestore by remember { mutableStateOf(autoRestore) }
    var widthText by remember(monitorWidth) { mutableStateOf(monitorWidth.toString()) }
    var heightText by remember(monitorHeight) { mutableStateOf(monitorHeight.toString()) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tablet_settings_title)) },
                navigationIcon = { TextButton(onClick = onClose) { Text(stringResource(R.string.dialog_cancel)) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { insets ->
        Column(
            Modifier
                .padding(insets)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            TabletSettingsCard(stringResource(R.string.tablet_section_pressure)) {
                PressureCurvePreview(
                    curve = selectedCurve,
                    customPoints = selectedCustomPoints,
                    modifier = Modifier.fillMaxWidth().height(190.dp),
                    onCustomPointsChanged = {
                        selectedCustomPoints = it
                        onCustomPointsChanged(it)
                    }
                )
                Spacer(Modifier.height(10.dp))
                ChipRow {
                    PressureCurveType.values().forEach { type ->
                        FilterChip(
                            selected = selectedCurve == type,
                            onClick = { selectedCurve = type; onCurveChanged(type) },
                            label = { Text(curveLabel(type)) }
                        )
                    }
                }
                SliderSetting(
                    label = stringResource(R.string.tablet_pressure_min),
                    value = selectedPressureMin,
                    range = 0f..0.5f,
                    onValueChange = { selectedPressureMin = it; onPressureMinChanged(it) }
                )
                SliderSetting(
                    label = stringResource(R.string.tablet_pressure_max),
                    value = selectedPressureMax,
                    range = 0.5f..1f,
                    onValueChange = { selectedPressureMax = it; onPressureMaxChanged(it) }
                )
            }

            TabletSettingsCard(stringResource(R.string.tablet_section_mapping)) {
                Text(stringResource(R.string.tablet_orientation), style = MaterialTheme.typography.labelLarge)
                ChipRow {
                    OrientationType.values().forEach { type ->
                        FilterChip(
                            selected = selectedOrientation == type,
                            onClick = { selectedOrientation = type; onOrientationChanged(type) },
                            label = { Text(orientationLabel(type)) }
                        )
                    }
                }
                SwitchSetting(stringResource(R.string.tablet_invert_x), selectedInvertX, { selectedInvertX = it; onInvertXChanged(it) })
                SwitchSetting(stringResource(R.string.tablet_invert_y), selectedInvertY, { selectedInvertY = it; onInvertYChanged(it) })
                SwitchSetting(stringResource(R.string.tablet_aspect_lock), selectedAspectLock, { selectedAspectLock = it; onAspectLockChanged(it) })
                Text(stringResource(R.string.tablet_monitor_res), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = widthText,
                        onValueChange = {
                            widthText = it.filter(Char::isDigit)
                            widthText.toIntOrNull()?.let(onMonitorWidthChanged)
                        },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("W") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    OutlinedTextField(
                        value = heightText,
                        onValueChange = {
                            heightText = it.filter(Char::isDigit)
                            heightText.toIntOrNull()?.let(onMonitorHeightChanged)
                        },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("H") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
            }

            TabletSettingsCard(stringResource(R.string.tablet_section_performance)) {
                SliderSetting(
                    label = stringResource(R.string.tablet_send_rate),
                    value = selectedSendRate.toFloat(),
                    range = 30f..200f,
                    onValueChange = { selectedSendRate = it.toInt(); onSendRateChanged(it.toInt()) }
                )
                SliderSetting(
                    label = stringResource(R.string.tablet_smoothing),
                    value = selectedSmoothing,
                    range = 0f..0.9f,
                    onValueChange = { selectedSmoothing = it; onSmoothingChanged(it) }
                )
            }

            TabletSettingsCard(stringResource(R.string.tablet_section_pen_button)) {
                ChipRow {
                    PenButtonAction.values().forEach { action ->
                        FilterChip(
                            selected = selectedButtonAction == action,
                            onClick = { selectedButtonAction = action; onButtonActionChanged(action) },
                            label = { Text(buttonActionLabel(action)) }
                        )
                    }
                }
            }

            TabletSettingsCard(stringResource(R.string.tablet_options)) {
                SwitchSetting(stringResource(R.string.tablet_haptic), selectedHaptic, { selectedHaptic = it; onHapticChanged(it) })
                SwitchSetting(stringResource(R.string.tablet_show_grid), selectedShowGrid, { selectedShowGrid = it; onShowGridChanged(it) })
                SwitchSetting(stringResource(R.string.tablet_auto_restore), selectedAutoRestore, { selectedAutoRestore = it; onAutoRestoreChanged(it) })
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun TabletSettingsCard(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) { content() }
}

@Composable
private fun SliderSetting(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column {
        Text("$label: ${"%.2f".format(value)}", style = MaterialTheme.typography.labelLarge)
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onValueChange, valueRange = range)
    }
}

@Composable
private fun SwitchSetting(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun curveLabel(type: PressureCurveType): String = when (type) {
    PressureCurveType.LINEAR -> stringResource(R.string.tablet_curve_linear)
    PressureCurveType.SOFT -> stringResource(R.string.tablet_curve_soft)
    PressureCurveType.FIRM -> stringResource(R.string.tablet_curve_firm)
    PressureCurveType.S_CURVE -> stringResource(R.string.tablet_curve_scurve)
    PressureCurveType.CUSTOM -> stringResource(R.string.tablet_curve_custom)
}

@Composable
private fun orientationLabel(type: OrientationType): String = when (type) {
    OrientationType.AUTO -> stringResource(R.string.tablet_orientation_auto)
    OrientationType.PORTRAIT -> stringResource(R.string.tablet_orientation_portrait)
    OrientationType.LANDSCAPE -> stringResource(R.string.tablet_orientation_landscape)
    OrientationType.LANDSCAPE_INV -> stringResource(R.string.tablet_orientation_landscape_inv)
}

@Composable
private fun buttonActionLabel(action: PenButtonAction): String = when (action) {
    PenButtonAction.RIGHT_CLICK -> stringResource(R.string.tablet_btn_right_click)
    PenButtonAction.MIDDLE_CLICK -> stringResource(R.string.tablet_btn_middle_click)
    PenButtonAction.ERASER -> stringResource(R.string.tablet_btn_eraser)
    PenButtonAction.DISABLED -> stringResource(R.string.tablet_btn_disabled)
}
