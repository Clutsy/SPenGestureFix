@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.spengesturefix

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties

sealed class BindingTarget {
    data class Gesture(val kind: GestureKind) : BindingTarget()
    data class WheelSlot(val index: Int) : BindingTarget()
}

private val WHEEL_COLOR_PRESETS = listOf(
    Color(0xFF29B6F6), Color(0xFF26C6DA), Color(0xFF66BB6A), Color(0xFF9CCC65),
    Color(0xFFFFEE58), Color(0xFFFFA726), Color(0xFFEF5350), Color(0xFFEC407A),
    Color(0xFFAB47BC), Color(0xFF7E57C2), Color(0xFF8D6E63), Color.White
)

private val LANGUAGE_CHOICES = listOf(
    null to R.string.settings_language_system,
    "en" to R.string.settings_language_en,
    "it" to R.string.settings_language_it,
    "es" to R.string.settings_language_es,
    "fr" to R.string.settings_language_fr,
    "de" to R.string.settings_language_de,
    "pt" to R.string.settings_language_pt,
    "nl" to R.string.settings_language_nl,
    "pl" to R.string.settings_language_pl,
    "tr" to R.string.settings_language_tr,
    "ru" to R.string.settings_language_ru,
    "uk" to R.string.settings_language_uk,
    "zh-CN" to R.string.settings_language_zh,
    "ja" to R.string.settings_language_ja,
    "ko" to R.string.settings_language_ko,
    "ar" to R.string.settings_language_ar,
    "hi" to R.string.settings_language_hi,
    "id" to R.string.settings_language_in
)

@Composable
fun MainComposeScreen(
    presence: PenPresenceState,
    serviceActive: Boolean,
    digitizerActive: Boolean,
    rootChecked: Boolean?,
    overlayGranted: Boolean,
    notificationGranted: Boolean,
    amoled: Boolean,
    autoStart: Boolean,
    batterySaver: Boolean,
    languageCode: String?,
    gestures: Map<GestureKind, PenAction>,
    wheelSlots: List<PenAction>,
    wheelColor: Color,
    wheelStyle: WheelStyle,
    wheelSlotCount: Int,
    onCheckRoot: () -> Unit,
    onRequestOverlay: () -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onAmoledChanged: (Boolean) -> Unit,
    onAutoStartChanged: (Boolean) -> Unit,
    onBatterySaverChanged: (Boolean) -> Unit,
    onLanguageChanged: (String?) -> Unit,
    onActionPicked: (BindingTarget, PenAction) -> Unit,
    onWheelColorChanged: (Color) -> Unit,
    onWheelStyleChanged: (WheelStyle) -> Unit,
    onWheelSlotCountChanged: (Int) -> Unit,
    onPickWheelSound: (Boolean) -> Unit,
    onClearWheelSound: (Boolean) -> Unit,
    onMoveWheelSlot: (Int, Int) -> Unit,
    onOpenNotes: () -> Unit,
    onOpenTablet: () -> Unit,
    onOpenTabletSettings: () -> Unit
) {
    val context = LocalContext.current
    val gestureKinds = remember { GestureKind.values().toList() }
    val emptyAction = remember(context) {
        PenAction(ActionType.NONE, ActionType.NONE.label(context))
    }
    var pickerTarget by remember { mutableStateOf<BindingTarget?>(null) }
    var languageDialog by remember { mutableStateOf(false) }
    var permissionDialog by remember { mutableStateOf(!overlayGranted || !notificationGranted) }
    // Hub navigation: null shows the section grid; a value opens that page.
    var section by rememberSaveable { mutableStateOf<DashboardSection?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val current = section
                    if (current == null) {
                        Column {
                            Text(
                                text = stringResource(R.string.app_name),
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(R.string.subtitle_device),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        Text(
                            text = stringResource(current.titleRes),
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                navigationIcon = {
                    if (section != null) {
                        IconButton(onClick = { section = null }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_nav_back),
                                contentDescription = stringResource(R.string.dialog_back)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { insets ->
        LazyColumn(
            modifier = Modifier
                .padding(insets)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {

            if (section == null) {
                // Hub: one tappable tile per section. Nothing else lives here:
                // the grid IS the home screen.
                item(key = "intro", contentType = "header") {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.home_tagline),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.home_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                item(key = "status", contentType = "card") {
                    DeviceStatusCard(
                        presence = presence,
                        serviceActive = serviceActive,
                        digitizerActive = digitizerActive,
                        rootChecked = rootChecked,
                        overlayGranted = overlayGranted,
                        notificationGranted = notificationGranted,
                        onCheckRoot = onCheckRoot,
                        onRequestOverlay = onRequestOverlay,
                        onStartService = onStartService,
                        onStopService = onStopService
                    )
                }

                item(key = "tiles", contentType = "grid") {
                    DashboardGrid(onOpen = { section = it })
                }

                item(key = "footer", contentType = "footer") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.footer_experimental),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(
                            onClick = {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/clutsy"))
                                )
                            }
                        ) {
                            Text(stringResource(R.string.made_by))
                        }
                    }
                }
            } else {
                when (section) {
                    DashboardSection.TABLET -> item(key = "tablet", contentType = "card") {
                        SectionCard(
                            title = stringResource(R.string.section_tablet_mode),
                            subtitle = stringResource(R.string.tablet_mode_subtitle),
                            iconRes = R.drawable.ic_act_screen_write
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = onOpenTablet, modifier = Modifier.weight(1f)) {
                                    Text(stringResource(R.string.btn_start_tablet))
                                }
                                OutlinedButton(onClick = onOpenTabletSettings, modifier = Modifier.weight(1f)) {
                                    Text(stringResource(R.string.btn_tablet_settings))
                                }
                            }
                        }
                    }

                    DashboardSection.WHEEL_LOOK -> item(key = "wheel_look", contentType = "card") {
                        SectionCard(
                            title = stringResource(R.string.section_wheel),
                            subtitle = stringResource(R.string.wheel_style_subtitle),
                            iconRes = R.drawable.ic_act_open_wheel,
                            accent = wheelColor
                        ) {
                            WheelStyleRow(
                                selected = wheelStyle,
                                onSelect = onWheelStyleChanged
                            )
                            Spacer(Modifier.height(12.dp))
                            WheelColorRow(
                                selected = wheelColor,
                                onSelect = onWheelColorChanged
                            )
                        }
                    }

                    DashboardSection.WHEEL_SLOTS -> item(key = "wheel_slots", contentType = "card") {
                        SectionCard(
                            title = stringResource(R.string.wheel_slot_count_title),
                            subtitle = stringResource(R.string.wheel_slots_subtitle),
                            iconRes = R.drawable.ic_sec_slots
                        ) {
                            // Never render more rows than configured slots: the
                            // classic style serves at most six authentic discs.
                            val slotRows = minOf(wheelSlotCount, wheelSlots.size)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                WheelConfig.SLOT_COUNT_CHOICES.forEach { count ->
                                    FilterChip(
                                        selected = wheelSlotCount == count,
                                        onClick = { onWheelSlotCountChanged(count) },
                                        label = { Text(count.toString()) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            repeat(slotRows) { index ->
                                ActionRow(
                                    title = stringResource(R.string.wheel_slot_label, index + 1),
                                    action = wheelSlots.getOrNull(index) ?: emptyAction,
                                    onClick = { pickerTarget = BindingTarget.WheelSlot(index) },
                                    onMoveUp = if (index > 0) {
                                        { onMoveWheelSlot(index, index - 1) }
                                    } else null,
                                    onMoveDown = if (index < slotRows - 1) {
                                        { onMoveWheelSlot(index, index + 1) }
                                    } else null
                                )
                                if (index < slotRows - 1) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f))
                                }
                            }
                        }
                    }

                    DashboardSection.WHEEL_SOUNDS -> item(key = "wheel_sounds", contentType = "card") {
                        SectionCard(
                            title = stringResource(R.string.wheel_sounds_title),
                            subtitle = stringResource(R.string.wheel_sounds_subtitle),
                            iconRes = R.drawable.ic_act_media_play
                        ) {
                            WheelSoundsRow(
                                onPick = onPickWheelSound,
                                onClear = onClearWheelSound
                            )
                        }
                    }

                    DashboardSection.GESTURES -> item(key = "gestures", contentType = "card") {
                        SectionCard(
                            title = stringResource(R.string.section_spen_button),
                            subtitle = stringResource(R.string.hint_tap_row),
                            iconRes = R.drawable.ic_act_none
                        ) {
                            gestureKinds.forEach { gesture ->
                                ActionRow(
                                    title = gestureLabel(gesture),
                                    action = gestures[gesture] ?: emptyAction,
                                    onClick = { pickerTarget = BindingTarget.Gesture(gesture) }
                                )
                                if (gesture != gestureKinds.last()) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f))
                                }
                            }
                        }
                    }

                    DashboardSection.NOTES -> item(key = "notes", contentType = "card") {
                        SectionCard(
                            title = stringResource(R.string.section_notes),
                            subtitle = stringResource(R.string.notes_card_subtitle),
                            iconRes = R.drawable.ic_act_quick_note
                        ) {
                            Button(onClick = onOpenNotes, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.btn_view_notes))
                            }
                        }
                    }

                    DashboardSection.SETTINGS -> item(key = "settings", contentType = "card") {
                        SectionCard(
                            title = stringResource(R.string.section_settings),
                            subtitle = stringResource(R.string.settings_subtitle),
                            iconRes = R.drawable.ic_act_open_wheel
                        ) {
                            SettingsRow(
                                title = stringResource(R.string.settings_language),
                                value = languageLabel(languageCode),
                                onClick = { languageDialog = true }
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f))
                            ToggleRow(
                                title = stringResource(R.string.settings_amoled),
                                subtitle = stringResource(R.string.settings_amoled_desc),
                                checked = amoled,
                                onCheckedChange = onAmoledChanged
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f))
                            ToggleRow(
                                title = stringResource(R.string.settings_autostart_pen),
                                subtitle = stringResource(R.string.settings_autostart_pen_desc),
                                checked = autoStart,
                                onCheckedChange = onAutoStartChanged
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f))
                            ToggleRow(
                                title = stringResource(R.string.settings_battery_saver),
                                subtitle = stringResource(R.string.settings_battery_saver_desc),
                                checked = batterySaver,
                                onCheckedChange = onBatterySaverChanged
                            )
                        }
                    }
                    null -> item(key = "empty", contentType = "card") {}
                }
            }
        }
    }

    if (languageDialog) {
        LanguageDialog(
            selected = languageCode,
            onDismiss = { languageDialog = false },
            onSelected = {
                languageDialog = false
                onLanguageChanged(it)
            }
        )
    }

    if (permissionDialog) {
        PermissionDialog(
            overlayGranted = overlayGranted,
            notificationGranted = notificationGranted,
            onGrant = {
                permissionDialog = false
                onRequestOverlay()
                onStartService()
            },
            onContinue = { permissionDialog = false }
        )
    }

    pickerTarget?.let { target ->
        ActionPickerSheet(
            target = target,
            onDismiss = { pickerTarget = null },
            onPicked = { action ->
                onActionPicked(target, action)
                pickerTarget = null
            }
        )
    }
}

@Composable
private fun DeviceStatusCard(
    presence: PenPresenceState,
    serviceActive: Boolean,
    digitizerActive: Boolean,
    rootChecked: Boolean?,
    overlayGranted: Boolean,
    notificationGranted: Boolean,
    onCheckRoot: () -> Unit,
    onRequestOverlay: () -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit
) {
    SectionCard(
        title = stringResource(R.string.section_status),
        subtitle = stringResource(R.string.status_subtitle)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusPill(presenceLabel(presence), presenceColor(presence))
            Spacer(Modifier.width(8.dp))
            StatusPill(
                if (serviceActive) stringResource(R.string.status_service_active)
                else stringResource(R.string.status_service_stopped),
                if (serviceActive) Color(0xFF73D7A5) else MaterialTheme.colorScheme.outline
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = if (digitizerActive) stringResource(R.string.status_digitizer_active)
            else stringResource(R.string.status_digitizer_missing),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCheckRoot, modifier = Modifier.weight(1f)) {
                Text(
                    when (rootChecked) {
                        true -> stringResource(R.string.root_ok)
                        false -> stringResource(R.string.root_failed)
                        null -> stringResource(R.string.btn_check_root)
                    }
                )
            }
            OutlinedButton(
                onClick = onRequestOverlay,
                modifier = Modifier.weight(1f)
            ) {
                Text(if (overlayGranted) stringResource(R.string.overlay_ready) else stringResource(R.string.btn_overlay_permission))
            }
        }
        Spacer(Modifier.height(8.dp))
        if (!notificationGranted) {
            Text(
                text = stringResource(R.string.notification_missing),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(6.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onStartService, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.btn_start))
            }
            OutlinedButton(onClick = onStopService, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.btn_stop))
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    subtitle: String? = null,
    iconRes: Int? = null,
    accent: Color? = null,
    content: @Composable () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (iconRes != null) {
                    Surface(
                        color = (accent ?: MaterialTheme.colorScheme.primary).copy(alpha = 0.16f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.size(34.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                painter = painterResource(iconRes),
                                contentDescription = null,
                                tint = accent ?: MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
            }
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun SettingsRow(title: String, value: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        trailingContent = { Text("⌄", color = MaterialTheme.colorScheme.onSurfaceVariant) },
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        colors = androidx.compose.material3.ListItemDefaults.colors(
            containerColor = Color.Transparent
        )
    )
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onCheckedChange) },
        colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
private fun ActionRow(
    title: String,
    action: PenAction,
    onClick: () -> Unit,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Text(
                action.label,
                maxLines = 1,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (onMoveUp != null || onMoveDown != null) {
                    if (onMoveUp != null) {
                        TextButton(
                            onClick = onMoveUp,
                            contentPadding = PaddingValues(horizontal = 6.dp),
                            modifier = Modifier.defaultMinSize(minWidth = 30.dp, minHeight = 30.dp)
                        ) { Text("▲", fontSize = 13.sp) }
                    }
                    if (onMoveDown != null) {
                        TextButton(
                            onClick = onMoveDown,
                            contentPadding = PaddingValues(horizontal = 6.dp),
                            modifier = Modifier.defaultMinSize(minWidth = 30.dp, minHeight = 30.dp)
                        ) { Text("▼", fontSize = 13.sp) }
                    }
                }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.size(34.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(action.type.iconResId),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
private fun StatusPill(label: String, color: Color) {
    Surface(
        color = color.copy(alpha = .16f),
        contentColor = color,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.height(32.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(color, CircleShape)
            )
            Spacer(Modifier.width(7.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun LanguageDialog(
    selected: String?,
    onDismiss: () -> Unit,
    onSelected: (String?) -> Unit
) {
    val choices = LANGUAGE_CHOICES
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_language)) },
        text = {
            // Keep the dialog bounded on compact phone displays while allowing every
            // locale to be reached with ordinary touch scrolling.
            LazyColumn(
                modifier = Modifier.heightIn(min = 56.dp, max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(
                    items = choices,
                    key = { (code, _) -> code ?: "system" }
                ) { (code, labelRes) ->
                    val label = stringResource(labelRes)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onSelected(code) }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = selected == code, onClick = { onSelected(code) })
                        Spacer(Modifier.width(8.dp))
                        Text(label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) } }
    )
}

@Composable
private fun PermissionDialog(
    overlayGranted: Boolean,
    notificationGranted: Boolean,
    onGrant: () -> Unit,
    onContinue: () -> Unit
) {
    val missing = buildList {
        if (!overlayGranted) add(stringResource(R.string.perm_overlay))
        if (!notificationGranted) add(stringResource(R.string.perm_notification))
    }
    AlertDialog(
        onDismissRequest = onContinue,
        title = { Text(stringResource(R.string.perm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                missing.forEach { Text("• $it") }
                Text(stringResource(R.string.perm_root))
            }
        },
        confirmButton = { Button(onClick = onGrant) { Text(stringResource(R.string.perm_grant_all)) } },
        dismissButton = { TextButton(onClick = onContinue) { Text(stringResource(R.string.perm_continue)) } },
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = false)
    )
}

@Composable
private fun ActionPickerSheet(
    target: BindingTarget,
    onDismiss: () -> Unit,
    onPicked: (PenAction) -> Unit
) {
    val context = LocalContext.current
    var selectedType by remember(target) { mutableStateOf<ActionType?>(null) }
    var shellCommand by remember(target) { mutableStateOf("") }
    var query by remember(target) { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val allApps = remember(context) { queryLauncherApps(context) }
    val actionTypes = remember { ActionType.values().toList() }
    val visibleApps = remember(query, allApps) {
        allApps.filter { it.label.contains(query, ignoreCase = true) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(
                text = when (target) {
                    is BindingTarget.Gesture -> gestureLabel(target.kind)
                    is BindingTarget.WheelSlot -> stringResource(R.string.wheel_slot_label, target.index + 1)
                },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            when {
                selectedType == null -> {
                    Text(stringResource(R.string.dialog_choose_action), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LazyColumn(Modifier.heightIn(max = 520.dp)) {
                        items(actionTypes, key = { it.name }) { type ->
                            ListItem(
                                headlineContent = { Text(type.label(context)) },
                                leadingContent = {
                                    Icon(
                                        painter = painterResource(type.iconResId),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (type.needsAppTarget || type.needsTextTarget) selectedType = type
                                        else onPicked(PenAction(type, type.label(context)))
                                    },
                                colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = Color.Transparent)
                            )
                        }
                    }
                }
                selectedType?.needsAppTarget == true -> {
                    Text(stringResource(R.string.dialog_choose_app), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.dialog_search_app)) }
                    )
                    LazyColumn(Modifier.heightIn(max = 470.dp)) {
                        items(visibleApps, key = { it.packageName }) { app ->
                            ListItem(
                                headlineContent = { Text(app.label) },
                                supportingContent = { Text(app.packageName) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val type = selectedType ?: return@clickable
                                        onPicked(PenAction(type, app.label, app.packageName))
                                    },
                                colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = Color.Transparent)
                            )
                        }
                    }
                }
                else -> {
                    Text(stringResource(R.string.dialog_shell_title), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = shellCommand,
                        onValueChange = { shellCommand = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        label = { Text(stringResource(R.string.dialog_shell_hint)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { selectedType = null }, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.dialog_back))
                        }
                        Button(
                            onClick = {
                                val type = selectedType ?: return@Button
                                if (shellCommand.isNotBlank()) {
                                    onPicked(PenAction(type, context.getString(R.string.command_prefix, shellCommand.take(24)), shellCommand))
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text(stringResource(R.string.dialog_ok)) }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

private data class LauncherApp(val label: String, val packageName: String)

private fun queryLauncherApps(context: Context): List<LauncherApp> {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return context.packageManager.queryIntentActivities(intent, 0)
        .map { LauncherApp(it.loadLabel(context.packageManager).toString(), it.activityInfo.packageName) }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
}

@Composable
private fun languageLabel(code: String?): String = when (code) {
    "en" -> stringResource(R.string.settings_language_en)
    "it" -> stringResource(R.string.settings_language_it)
    "es" -> stringResource(R.string.settings_language_es)
    "fr" -> stringResource(R.string.settings_language_fr)
    "de" -> stringResource(R.string.settings_language_de)
    "pt" -> stringResource(R.string.settings_language_pt)
    "nl" -> stringResource(R.string.settings_language_nl)
    "pl" -> stringResource(R.string.settings_language_pl)
    "tr" -> stringResource(R.string.settings_language_tr)
    "ru" -> stringResource(R.string.settings_language_ru)
    "uk" -> stringResource(R.string.settings_language_uk)
    "zh-CN" -> stringResource(R.string.settings_language_zh)
    "ja" -> stringResource(R.string.settings_language_ja)
    "ko" -> stringResource(R.string.settings_language_ko)
    "ar" -> stringResource(R.string.settings_language_ar)
    "hi" -> stringResource(R.string.settings_language_hi)
    "id" -> stringResource(R.string.settings_language_in)
    else -> stringResource(R.string.settings_language_system)
}

@Composable
private fun gestureLabel(gesture: GestureKind): String = when (gesture) {
    GestureKind.CLICK -> stringResource(R.string.gesture_click)
    GestureKind.DOUBLE_CLICK -> stringResource(R.string.gesture_double_click)
    GestureKind.LONG_PRESS -> stringResource(R.string.gesture_long_press)
}

@Composable
private fun presenceLabel(presence: PenPresenceState): String = when (presence) {
    PenPresenceState.UNKNOWN -> stringResource(R.string.status_presence_unknown)
    PenPresenceState.INSERTED -> stringResource(R.string.status_presence_inserted)
    PenPresenceState.REMOVED -> stringResource(R.string.status_presence_extracted)
}

private fun presenceColor(presence: PenPresenceState): Color = when (presence) {
    PenPresenceState.UNKNOWN -> Color(0xFFAAA7B7)
    PenPresenceState.INSERTED -> Color(0xFF73D7A5)
    PenPresenceState.REMOVED -> Color(0xFFFFC470)
}


/**
 * Parses a custom color from free text: hex (#RGB, #RRGGBB, #AARRGGBB, with
 * optional "0x" prefix) or a decimal RGB triple ("R,G,B", semicolons or
 * spaces as separators). Returns null when the text is not a valid color.
 * Internal so the pure parsing logic stays unit-testable.
 */
internal fun parseWheelColorInput(raw: String): Color? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    val hex = text.removePrefix("#").removePrefix("0x").removePrefix("0X")
    if (hex.isNotEmpty() && hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
        return when (hex.length) {
            3 -> {
                val channels = hex.map { it.digitToInt(16) * 17 }
                Color(0xFF000000.toInt() or (channels[0] shl 16) or (channels[1] shl 8) or channels[2])
            }
            6 -> hex.toLongOrNull(16)?.let { Color((0xFF000000L or it).toInt()) }
            8 -> hex.toLongOrNull(16)?.let { Color(it.toInt()) }
            else -> null
        }
    }
    val parts = text.split(",", ";", " ").filter { it.isNotBlank() }
    if (parts.size == 3) {
        val channels = parts.map { it.toIntOrNull() }
        if (channels.all { it != null && it in 0..255 }) {
            return Color(
                0xFF000000.toInt() or (channels[0]!! shl 16) or (channels[1]!! shl 8) or channels[2]!!
            )
        }
    }
    return null
}

@Composable
private fun WheelColorRow(
    selected: Color,
    onSelect: (Color) -> Unit
) {
    val context = LocalContext.current
    val presets = WHEEL_COLOR_PRESETS
    // Recent colors refresh whenever the applied color changes (the caller
    // updates `selected`), so the row always mirrors persistence.
    val recent = remember(selected) { WheelConfig.getRecentWheelColors(context) }
    var showPicker by rememberSaveable { mutableStateOf(false) }

    Column {
        Text(
            text = stringResource(R.string.wheel_color_title),
            style = MaterialTheme.typography.labelLarge
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = stringResource(R.string.wheel_color_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))

        // Real color picker: HSV plane + hue slider + hex sync, like a
        // desktop picker — the old text-only entry could never compete.
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { showPicker = true }
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Box(
                    Modifier
                        .size(30.dp)
                        .background(selected, CircleShape)
                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.wheel_color_choose),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "#%06X".format(0xFFFFFF and (selected.toArgb().toLong().toInt())),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            presets.forEach { color -> ColorSwatch(color, selected, onSelect) }
        }

        if (showPicker) {
            ColorPickerDialog(
                initial = selected,
                onDismiss = { showPicker = false },
                onApply = { color ->
                    showPicker = false
                    onSelect(color)
                }
            )
        }

        if (recent.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.wheel_color_recent_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                recent.forEach { color -> ColorSwatch(color, selected, onSelect) }
            }
        }
    }
}

@Composable
private fun WheelStyleRow(
    selected: WheelStyle,
    onSelect: (WheelStyle) -> Unit
) {
    Column {
        Text(
            text = stringResource(R.string.wheel_style_title),
            style = MaterialTheme.typography.labelLarge
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = stringResource(R.string.wheel_style_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            WheelStyle.values().forEach { style ->
                FilterChip(
                    selected = selected == style,
                    onClick = { onSelect(style) },
                    label = { Text(stringResource(style.labelResId)) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun WheelSoundsRow(
    onPick: (Boolean) -> Unit,
    onClear: (Boolean) -> Unit
) {
    val context = LocalContext.current
    Column {
        Text(
            text = stringResource(R.string.wheel_sounds_title),
            style = MaterialTheme.typography.labelLarge
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = stringResource(R.string.wheel_sounds_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        listOf(
            Triple(true, stringResource(R.string.wheel_sound_open), WheelSoundStore.getOpenSound(context)),
            Triple(false, stringResource(R.string.wheel_sound_close), WheelSoundStore.getCloseSound(context))
        ).forEach { (open, label, uri) ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = uri?.lastPathSegment ?: stringResource(R.string.wheel_sound_none),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                    TextButton(onClick = { onPick(open) }) {
                        Text(stringResource(R.string.wheel_sound_choose))
                    }
                    if (uri != null) {
                        TextButton(onClick = { onClear(open) }) {
                            Text(stringResource(R.string.dialog_cancel))
                        }
                    }
                    IconButton(onClick = { WheelSoundStore.play(context, open) }) {
                        Icon(painterResource(R.drawable.ic_act_media_play), contentDescription = null)
                    }
                    if (uri == null) {
                        // Keep an anchor view so spacing stays stable when empty.
                        Spacer(Modifier.width(48.dp))
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun ColorSwatch(
    color: Color,
    selected: Color,
    onSelect: (Color) -> Unit
) {
    val isSelected = selected == color
    Surface(
        color = color,
        shape = CircleShape,
        modifier = Modifier
            .size(38.dp)
            .then(
                if (isSelected) Modifier.border(3.dp, Color.White, CircleShape)
                else Modifier.border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            )
            .clickable { onSelect(color) }
    ) {}
}

/** Home sections of the hub. Each tile opens its own page. */
enum class DashboardSection(val titleRes: Int, val iconRes: Int) {
    TABLET(R.string.section_tablet_mode, R.drawable.ic_act_screen_write),
    WHEEL_LOOK(R.string.section_wheel, R.drawable.ic_act_open_wheel),
    WHEEL_SLOTS(R.string.wheel_slot_count_title, R.drawable.ic_sec_slots),
    WHEEL_SOUNDS(R.string.wheel_sounds_title, R.drawable.ic_act_media_play),
    GESTURES(R.string.section_spen_button, R.drawable.ic_sec_gestures),
    NOTES(R.string.section_notes, R.drawable.ic_act_quick_note),
    SETTINGS(R.string.section_settings, R.drawable.ic_act_settings)
}

@Composable
private fun DashboardGrid(onOpen: (DashboardSection) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        DashboardSection.entries.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { entry ->
                    SectionTile(entry, Modifier.weight(1f), onOpen)
                }
                if (pair.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SectionTile(
    entry: DashboardSection,
    modifier: Modifier,
    onOpen: (DashboardSection) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(18.dp),
        modifier = modifier
            .heightIn(min = 96.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable { onOpen(entry) }
    ) {
        Column(Modifier.padding(14.dp)) {
            Icon(
                painter = painterResource(entry.iconRes),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp)
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(entry.titleRes),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/** HSV coordinates of [color] (h in 0..360, s/v in 0..1). */
internal fun colorToHsv(color: Color): Triple<Float, Float, Float> {
    val hsv = FloatArray(3)
    android.graphics.Color.RGBToHSV(
        (color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt(), hsv
    )
    return Triple(hsv[0], hsv[1], hsv[2])
}

internal fun hsvToColor(hue: Float, sat: Float, value: Float): Color =
    Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value)))

@Composable
private fun ColorPickerDialog(
    initial: Color,
    onDismiss: () -> Unit,
    onApply: (Color) -> Unit
) {
    val startHsv = remember { colorToHsv(initial) }
    var hue by rememberSaveable { mutableStateOf(startHsv.first) }
    var sat by rememberSaveable { mutableStateOf(startHsv.second) }
    var value by rememberSaveable { mutableStateOf(startHsv.third) }
    var textInput by rememberSaveable { mutableStateOf("") }
    val current = hsvToColor(hue, sat, value)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.wheel_color_title)) },
        text = {
            Column {
                // SV plane: x = saturation, y = value, at the current hue.
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .pointerInput(hue) {
                            detectDragGestures { change, _ ->
                                change.consume()
                                sat = change.position.x.coerceIn(0f, size.width.toFloat()) /
                                    size.width.coerceAtLeast(1).toFloat()
                                value = 1f - change.position.y.coerceIn(0f, size.height.toFloat()) /
                                    size.height.coerceAtLeast(1).toFloat()
                            }
                        }
                        .pointerInput(hue) {
                            detectTapGestures { offset ->
                                sat = offset.x.coerceIn(0f, size.width.toFloat()) /
                                    size.width.coerceAtLeast(1).toFloat()
                                value = 1f - offset.y.coerceIn(0f, size.height.toFloat()) /
                                    size.height.coerceAtLeast(1).toFloat()
                            }
                        }
                ) {
                    val width = size.width
                    val height = size.height
                    val steps = 24
                    for (column in 0 until steps) {
                        for (row in 0 until steps) {
                            val s = column / (steps - 1).toFloat()
                            val v = 1f - row / (steps - 1).toFloat()
                            drawRect(
                                color = hsvToColor(hue, s, v),
                                topLeft = Offset(column * width / steps, row * height / steps),
                                size = androidx.compose.ui.geometry.Size(
                                    width / steps + 1f, height / steps + 1f
                                )
                            )
                        }
                    }
                    // selection ring
                    drawCircle(
                        color = Color.White,
                        radius = 9.dp.toPx(),
                        center = Offset(sat * width, (1f - value) * height),
                        style = Stroke(width = 3.dp.toPx())
                    )
                    drawCircle(
                        color = Color.Black.copy(alpha = 0.5f),
                        radius = 9.dp.toPx(),
                        center = Offset(sat * width, (1f - value) * height),
                        style = Stroke(width = 1.5.dp.toPx())
                    )
                }
                Spacer(Modifier.height(8.dp))
                // Hue slider drawn as a rainbow gradient with the thumb ring.
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(26.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .pointerInput(Unit) {
                            detectDragGestures { change, _ ->
                                change.consume()
                                hue = (change.position.x.coerceIn(0f, size.width.toFloat()) /
                                    size.width.coerceAtLeast(1).toFloat()) * 360f
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures { offset ->
                                hue = (offset.x.coerceIn(0f, size.width.toFloat()) /
                                    size.width.coerceAtLeast(1).toFloat()) * 360f
                            }
                        }
                ) {
                    val rainbow = Brush.horizontalGradient(
                        List(13) { i -> hsvToColor(i * 30f, 1f, 1f) }
                    )
                    drawRoundRect(
                        brush = rainbow,
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height)
                    )
                    drawCircle(
                        color = Color.White,
                        radius = size.height * 0.42f,
                        center = Offset(hue / 360f * size.width, size.height / 2f),
                        style = Stroke(width = 3.dp.toPx())
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(34.dp)
                            .background(current, CircleShape)
                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    )
                    Spacer(Modifier.width(10.dp))
                    OutlinedTextField(
                        value = textInput,
                        onValueChange = { raw ->
                            textInput = raw
                            parseWheelColorInput(raw)?.let { parsed ->
                                val hsv = colorToHsv(parsed)
                                hue = hsv.first; sat = hsv.second; value = hsv.third
                            }
                        },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.wheel_color_hint)) }
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onApply(current) }) {
                Text(stringResource(R.string.wheel_color_apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        }
    )
}
