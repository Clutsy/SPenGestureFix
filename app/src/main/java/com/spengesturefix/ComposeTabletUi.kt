@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.spengesturefix

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val PreviewSurfaceDark = Color(0xFF0A0A0E)

@Composable
fun TabletModeComposeScreen(
    frame: TabletFrame,
    running: Boolean,
    status: String,
    ip: String?,
    showGrid: Boolean,
    framesSent: Long,
    pcConnected: Boolean,
    previewConnected: Boolean,
    onToggle: () -> Unit,
    onSettings: () -> Unit,
    onExit: () -> Unit
) {
    var previewOpen by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(PreviewSurfaceDark)
    ) {
        TabletPreviewCanvas(frame, showGrid, Modifier.fillMaxSize())

        // Floating glass status bar: state, live pressure, and sent frames.
        Surface(
            color = Color(0xD20D0D12),
            contentColor = Color.White,
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(9.dp)
                            .background(
                                if (running) Color(0xFF73D7A5) else Color(0xFF5A5A66),
                                CircleShape
                            )
                    )
                    Spacer(Modifier.width(9.dp))
                    Text(
                        text = status.ifBlank {
                            if (running) stringResource(R.string.tablet_status_active)
                            else stringResource(R.string.tablet_status_ready)
                        },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (running) Color(0xFF73D7A5) else Color(0xFFC7C5D0)
                    )
                    Spacer(Modifier.width(10.dp))
                    PressureBadge(frame.pressure)
                }
                if (running) {
                    Spacer(Modifier.height(7.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.tablet_frames_sent, framesSent),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF8E8E9A)
                        )
                        Spacer(Modifier.width(10.dp))
                        PreviewPill(
                            enabled = running,
                            connected = previewConnected,
                            onClick = { if (running) previewOpen = true },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!running && !ip.isNullOrBlank()) {
                Text(
                    text = stringResource(R.string.tablet_connect_to, ip, TabletNetworkServer.PORT),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFF8E8E9A)
                )
            }
            Button(
                onClick = onToggle,
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (running) Color(0xFF2A2A33) else MaterialTheme.colorScheme.primary,
                    contentColor = if (running) Color.White else MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier
                    .fillMaxWidth(0.62f)
                    .height(56.dp)
            ) {
                    Text(
                        text = if (running) {
                            stringResource(R.string.tablet_stop)
                        } else {
                            stringResource(R.string.btn_start_tablet)
                        },
                        fontWeight = FontWeight.SemiBold
                    )
                }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onSettings,
                    shape = RoundedCornerShape(14.dp)
                ) { Text(stringResource(R.string.btn_tablet_settings)) }
                OutlinedButton(
                    onClick = onExit,
                    shape = RoundedCornerShape(14.dp)
                ) { Text(stringResource(R.string.dialog_cancel)) }
            }
        }
    }

    if (previewOpen) {
        PcPreviewOverlay(
            connected = previewConnected,
            onClose = { previewOpen = false }
        )
    }
}

@Composable
private fun PressureBadge(pressure: Float) {
    Surface(
        color = Color(0x1E73D7A5),
        contentColor = Color(0xFF73D7A5),
        shape = RoundedCornerShape(50)
    ) {
        Text(
            text = "P ${(pressure * 100).toInt()}%",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun PreviewPill(
    enabled: Boolean,
    connected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(50),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 12.dp, vertical = 4.dp
        ),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = if (connected) Color(0xFF1E4D3A) else Color(0xFF22222B),
            contentColor = if (connected) Color(0xFF8FF0BF) else Color(0xFFC7C5D0),
            disabledContainerColor = Color(0xFF17171D),
            disabledContentColor = Color(0xFF5A5A66)
        ),
        modifier = modifier.height(30.dp)
    ) {
        Box(Modifier.size(7.dp).background(
            if (connected) Color(0xFF8FF0BF) else Color(0xFF5A5A66),
            CircleShape
        ))
        Spacer(Modifier.width(7.dp))
        Text(
            text = stringResource(R.string.tablet_preview_button),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

/**
 * True fullscreen live view of the PC screen. Rendered as a plain Box over
 * the whole display (black backdrop, no dialog chrome) so video watching on
 * the phone is as smooth and immersive as the stream allows, with a live FPS
 * counter making stream health obvious at a glance.
 */
@Composable
private fun PcPreviewOverlay(connected: Boolean, onClose: () -> Unit) {
    var frameTick by remember { mutableStateOf(0) }
    var fps by remember { mutableStateOf(0f) }
    var lastFrames by remember { mutableStateOf(0L) }
    var lastFpsAt by remember { mutableStateOf(0L) }

    androidx.compose.runtime.LaunchedEffect(connected) {
        lastFrames = TabletPreviewServer.framesReceived()
        lastFpsAt = android.os.SystemClock.elapsedRealtime()
        while (connected) {
            frameTick++
            val now = android.os.SystemClock.elapsedRealtime()
            val frames = TabletPreviewServer.framesReceived()
            val elapsed = now - lastFpsAt
            if (elapsed >= 500L) {
                fps = (frames - lastFrames) * 1000f / elapsed
                lastFrames = frames
                lastFpsAt = now
            }
            kotlinx.coroutines.delay(66L)
        }
    }

    val bitmap: Bitmap? = if (connected) {
        frameTick // read to subscribe to the polling loop
        TabletPreviewServer.lastFrame()
    } else {
        null
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.tablet_preview_title),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (connected) {
                        stringResource(R.string.tablet_preview_waiting)
                    } else {
                        stringResource(R.string.tablet_preview_hint)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF8E8E9A),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.tablet_preview_port, TabletPreviewServer.PORT),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF5A5A66)
                )
            }
        }

        Surface(
            color = Color(0xB30D0D12),
            contentColor = Color.White,
            shape = RoundedCornerShape(50),
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(
                            if (connected) Color(0xFF8FF0BF) else Color(0xFF5A5A66),
                            CircleShape
                        )
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.tablet_preview_title),
                    style = MaterialTheme.typography.labelMedium
                )
                if (connected) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "%.0f fps".format(fps),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF8FF0BF),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Surface(
            onClick = onClose,
            color = Color(0xB30D0D12),
            contentColor = Color.White,
            shape = CircleShape,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(12.dp)
        ) {
            Text(
                text = "✕",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
            )
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
    mappingMode: MappingMode,
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
    onMappingModeChanged: (MappingMode) -> Unit,
    onMonitorWidthChanged: (Int) -> Unit,
    onMonitorHeightChanged: (Int) -> Unit,
    onSendRateChanged: (Int) -> Unit,
    onSmoothingChanged: (Float) -> Unit,
    onButtonActionChanged: (PenButtonAction) -> Unit,
    onHapticChanged: (Boolean) -> Unit,
    onShowGridChanged: (Boolean) -> Unit,
    onAutoRestoreChanged: (Boolean) -> Unit,
    onAutoDetectResolution: () -> Pair<Int, Int>?,
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
    var selectedMappingMode by remember { mutableStateOf(mappingMode) }
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
                Text(stringResource(R.string.tablet_section_mapping), style = MaterialTheme.typography.labelLarge)
                ChipRow {
                    MappingMode.values().forEach { mode ->
                        FilterChip(
                            selected = selectedMappingMode == mode,
                            onClick = {
                                selectedMappingMode = mode
                                onMappingModeChanged(mode)
                            },
                            label = { Text(mappingModeLabel(mode)) }
                        )
                    }
                }
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
                OutlinedButton(
                    onClick = {
                        onAutoDetectResolution()?.let { (width, height) ->
                            widthText = width.toString()
                            heightText = height.toString()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.tablet_auto_detect_resolution))
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
        Text(
            "$label: ${"%.2f".format(value)}",
            style = MaterialTheme.typography.labelLarge
        )
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
private fun mappingModeLabel(mode: MappingMode): String = when (mode) {
    MappingMode.FULL_SCREEN -> stringResource(R.string.tablet_mapping_full)
    MappingMode.CUSTOM_AREA -> stringResource(R.string.tablet_mapping_custom)
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
