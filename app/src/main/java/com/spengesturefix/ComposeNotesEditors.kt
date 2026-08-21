@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.spengesturefix

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickNoteComposeScreen(
    initialText: String,
    editing: Boolean,
    onSave: (String) -> Unit,
    onOpenNotes: () -> Unit,
    onCall: (String) -> Unit,
    onMaps: (String) -> Unit,
    onClose: () -> Unit
) {
    var text by remember { mutableStateOf(initialText) }
    val phone = remember(text) { detectPhoneNumberForUi(text) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (editing) stringResource(R.string.notes_edit_title) else stringResource(R.string.notes_new_title)) },
                navigationIcon = { TextButton(onClick = onClose) { Text(stringResource(R.string.dialog_cancel)) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { insets ->
        Column(
            modifier = Modifier
                .padding(insets)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it.take(4000) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 7,
                        maxLines = 14,
                        label = { Text(stringResource(R.string.hint_quick_note)) },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Default
                        )
                    )
                    Text(
                        text = stringResource(R.string.notes_character_count, text.length),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    )
                    if (phone != null) {
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(onClick = { onCall(phone) }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.btn_call_number))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { onMaps(text) },
                            enabled = text.isNotBlank(),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.btn_search_maps))
                        }
                        OutlinedButton(onClick = onOpenNotes, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.btn_view_notes))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { if (text.isNotBlank()) onSave(text) },
                        enabled = text.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (editing) stringResource(R.string.btn_update_note) else stringResource(R.string.btn_save_note))
                    }
                }
            }
        }
    }
}

@Composable
fun NotesListComposeScreen(
    notes: List<QuickNote>,
    onNew: () -> Unit,
    onEdit: (QuickNote) -> Unit,
    onDelete: (QuickNote) -> Unit,
    onShare: (QuickNote) -> Unit,
    onCopy: (QuickNote) -> Unit,
    onClose: () -> Unit
) {
    var noteToDelete by remember { mutableStateOf<QuickNote?>(null) }
    var query by remember { mutableStateOf("") }
    val visibleNotes = remember(notes, query) {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty()) notes
        else notes.filter { it.text.contains(normalizedQuery, ignoreCase = true) }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.section_notes)) },
                navigationIcon = { TextButton(onClick = onClose) { Text(stringResource(R.string.dialog_cancel)) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        bottomBar = {
            Button(
                onClick = onNew,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .navigationBarsPadding()
            ) { Text(stringResource(R.string.notes_new_title)) }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { insets ->
        if (notes.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(insets)
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    stringResource(R.string.notes_empty_list),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(insets)
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    singleLine = true,
                    label = { Text(stringResource(R.string.notes_search)) }
                )
                if (visibleNotes.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            stringResource(R.string.notes_no_match),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(
                            items = visibleNotes,
                            key = { note -> note.timestamp },
                            contentType = { "note" }
                        ) { note ->
                            NoteCard(
                                note = note,
                                onEdit = { onEdit(note) },
                                onDelete = { noteToDelete = note },
                                onShare = { onShare(note) },
                                onCopy = { onCopy(note) }
                            )
                        }
                        item { Spacer(Modifier.height(90.dp)) }
                    }
                }
            }
        }
    }

    noteToDelete?.let { note ->
        AlertDialog(
            onDismissRequest = { noteToDelete = null },
            title = { Text(stringResource(R.string.notes_delete)) },
            text = { Text(stringResource(R.string.notes_confirm_delete)) },
            confirmButton = {
                Button(onClick = { onDelete(note); noteToDelete = null }) {
                    Text(stringResource(R.string.dialog_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { noteToDelete = null }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            }
        )
    }
}

@Composable
private fun NoteCard(
    note: QuickNote,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    onCopy: () -> Unit
) {
    val format = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                format.format(Date(note.timestamp)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Text(note.text, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onEdit) { Text(stringResource(R.string.notes_edit)) }
                TextButton(onClick = onCopy) { Text(stringResource(android.R.string.copy)) }
                TextButton(onClick = onShare) { Text(stringResource(R.string.notes_share)) }
                TextButton(onClick = onDelete) { Text(stringResource(R.string.notes_delete)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenWriteComposeScreen(
    bitmap: Bitmap,
    onSave: (Bitmap) -> Unit,
    onClose: () -> Unit
) {
    var color by remember { mutableStateOf(Color(0xFFFF5252)) }
    var strokes by remember { mutableStateOf<List<ComposeStroke>>(emptyList()) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.action_screen_write)) },
                navigationIcon = { TextButton(onClick = onClose) { Text(stringResource(R.string.dialog_cancel)) } },
                actions = {
                    TextButton(onClick = { strokes = strokes.dropLast(1) }, enabled = strokes.isNotEmpty()) {
                        Text(stringResource(R.string.editor_undo))
                    }
                    TextButton(onClick = {
                        onSave(flattenComposeStrokes(bitmap, strokes, viewport.width, viewport.height))
                    }) { Text(stringResource(R.string.editor_save)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = Color.Black
    ) { insets ->
        Column(Modifier.padding(insets).fillMaxSize()) {
            DrawingSurface(
                bitmap = bitmap,
                color = color,
                strokes = strokes,
                onStrokesChanged = { strokes = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .onSizeChanged { viewport = it }
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf(
                    Color(0xFFFF5252), Color(0xFFFFD740), Color(0xFF69F0AE),
                    Color.White, Color(0xFF40C4FF)
                ).forEach { swatch ->
                    Spacer(
                        Modifier
                            .size(30.dp)
                            .clip(RoundedCornerShape(50))
                            .background(swatch)
                            .clickable { color = swatch }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartSelectComposeScreen(
    bitmap: Bitmap,
    onSave: (android.graphics.Rect) -> Unit,
    onClose: () -> Unit
) {
    var selection by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.action_smart_select)) },
                navigationIcon = { TextButton(onClick = onClose) { Text(stringResource(R.string.dialog_cancel)) } },
                actions = {
                    TextButton(onClick = {
                        val rect = selection?.let {
                            mapSelectionToBitmap(it, bitmap, viewport.width, viewport.height)
                        }
                        if (rect != null) onSave(rect)
                    }, enabled = selection != null) {
                        Text(stringResource(R.string.editor_crop_save))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = Color.Black
    ) { insets ->
        SelectionSurface(
            bitmap = bitmap,
            selection = selection,
            onSelectionChanged = { selection = it },
            modifier = Modifier
                .padding(insets)
                .fillMaxSize()
                .onSizeChanged { viewport = it }
        )
    }
}

private fun detectPhoneNumberForUi(text: String): String? =
    Regex("[+]?[0-9][0-9 ()-]{6,}[0-9]").find(text)?.value?.trim()
