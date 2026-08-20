@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.denis.spenfix

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
    languageCode: String?,
    gestures: Map<GestureKind, PenAction>,
    wheelSlots: List<PenAction>,
    hasBackground: Boolean,
    onCheckRoot: () -> Unit,
    onRequestOverlay: () -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onAmoledChanged: (Boolean) -> Unit,
    onAutoStartChanged: (Boolean) -> Unit,
    onLanguageChanged: (String?) -> Unit,
    onActionPicked: (BindingTarget, PenAction) -> Unit,
    onPickBackground: () -> Unit,
    onClearBackground: () -> Unit,
    onOpenNotes: () -> Unit,
    onOpenTablet: () -> Unit,
    onOpenTabletSettings: () -> Unit
) {
    var pickerTarget by remember { mutableStateOf<BindingTarget?>(null) }
    var languageDialog by remember { mutableStateOf(false) }
    var permissionDialog by remember { mutableStateOf(!overlayGranted || !notificationGranted) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
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
            item {
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

            item {
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

            item {
                SectionCard(
                    title = stringResource(R.string.section_settings),
                    subtitle = stringResource(R.string.settings_subtitle)
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
                }
            }

            item {
                SectionCard(
                    title = stringResource(R.string.section_spen_button),
                    subtitle = stringResource(R.string.hint_tap_row)
                ) {
                    GestureKind.values().forEach { gesture ->
                        ActionRow(
                            title = gestureLabel(gesture),
                            action = gestures[gesture] ?: PenAction(
                                ActionType.NONE,
                                ActionType.NONE.label(LocalContext.current)
                            ),
                            onClick = { pickerTarget = BindingTarget.Gesture(gesture) }
                        )
                        if (gesture != GestureKind.values().last()) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f))
                        }
                    }
                }
            }

            item {
                SectionCard(
                    title = stringResource(R.string.section_wheel),
                    subtitle = stringResource(R.string.wheel_slots_subtitle)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(onClick = onPickBackground, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.btn_pick_background))
                        }
                        if (hasBackground) {
                            TextButton(onClick = onClearBackground) {
                                Text(stringResource(R.string.btn_clear_background))
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    repeat(WheelConfig.SLOT_COUNT) { index ->
                        ActionRow(
                            title = stringResource(R.string.wheel_slot_label, index + 1),
                            action = wheelSlots.getOrNull(index) ?: PenAction(
                                ActionType.NONE,
                                ActionType.NONE.label(LocalContext.current)
                            ),
                            onClick = { pickerTarget = BindingTarget.WheelSlot(index) }
                        )
                        if (index < WheelConfig.SLOT_COUNT - 1) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f))
                        }
                    }
                }
            }

            item {
                SectionCard(
                    title = stringResource(R.string.section_notes),
                    subtitle = stringResource(R.string.notes_card_subtitle)
                ) {
                    Button(onClick = onOpenNotes, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.btn_view_notes))
                    }
                }
            }

            item {
                SectionCard(
                    title = stringResource(R.string.section_tablet_mode),
                    subtitle = stringResource(R.string.tablet_mode_subtitle)
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

            item {
                Text(
                    text = stringResource(R.string.footer_experimental),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                )
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
    content: @Composable () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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
private fun ActionRow(title: String, action: PenAction, onClick: () -> Unit) {
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
            Text(action.type.icon, fontSize = 20.sp)
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
        shape = RoundedCornerShape(50),
        modifier = Modifier.height(32.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(Modifier.size(7.dp).background(color, RoundedCornerShape(50)))
            Spacer(Modifier.width(6.dp))
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
    val choices = listOf(
        null to stringResource(R.string.settings_language_system),
        "en" to stringResource(R.string.settings_language_en),
        "it" to stringResource(R.string.settings_language_it),
        "es" to stringResource(R.string.settings_language_es)
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_language)) },
        text = {
            Column {
                choices.forEach { (code, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelected(code) }
                            .padding(vertical = 6.dp),
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
                        items(ActionType.values().toList(), key = { it.name }) { type ->
                            ListItem(
                                headlineContent = { Text(type.label(context)) },
                                leadingContent = { Text(type.icon, fontSize = 22.sp) },
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
