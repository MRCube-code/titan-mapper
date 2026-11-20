package com.titanmapper.feature.profiles

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.titanmapper.core.model.MappingProfile
import com.titanmapper.core.ui.components.ConfirmDialog
import com.titanmapper.core.ui.components.EmptyState
import com.titanmapper.core.ui.theme.TitanSpacing
import com.titanmapper.core.ui.theme.toColor
import kotlinx.coroutines.launch

@Composable
fun ProfilesRoute(onEditProfile: (String) -> Unit, viewModel: ProfilesViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showCreate by remember { mutableStateOf(false) }
    var exportText by remember { mutableStateOf<String?>(null) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
            if (text != null) viewModel.onEvent(ProfilesUiEvent.ImportFrom(text))
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        val text = exportText
        if (uri != null && text != null) {
            scope.launch { context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(text) } }
        }
        exportText = null
    }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { showCreate = true }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("New profile") })
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(modifier = Modifier.fillMaxWidth().padding(TitanSpacing.l), horizontalArrangement = Arrangement.spacedBy(TitanSpacing.s)) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = { viewModel.onEvent(ProfilesUiEvent.SetQuery(it)) },
                    label = { Text("Search profiles") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { importLauncher.launch(arrayOf("application/json")) }) {
                    Icon(Icons.Filled.Upload, contentDescription = "Import")
                }
            }

            if (state.filtered.isEmpty()) {
                EmptyState(
                    icon = Icons.Filled.Add,
                    title = if (state.profiles.isEmpty()) "No profiles yet" else "No matches",
                    message = "Create a profile to start mapping your keyboard and mouse."
                )
            } else {
                LazyColumn(contentPadding = PaddingValues(horizontal = TitanSpacing.l)) {
                    items(state.filtered, key = { it.id }) { profile ->
                        ProfileRow(
                            profile = profile,
                            onClick = { onEditProfile(profile.id) },
                            onToggle = { viewModel.onEvent(ProfilesUiEvent.ToggleEnabled(profile.id, it)) },
                            onDuplicate = { viewModel.onEvent(ProfilesUiEvent.Duplicate(profile.id)) },
                            onDelete = { viewModel.onEvent(ProfilesUiEvent.RequestDelete(profile.id)) },
                            onShare = {
                                viewModel.onEvent(ProfilesUiEvent.ExportOne(profile.id) { text ->
                                    exportText = text
                                    exportLauncher.launch("${profile.name}.titanprofile.json")
                                })
                            }
                        )
                    }
                }
            }
        }
    }

    state.message?.let { msg ->
        AlertDialog(
            onDismissRequest = { viewModel.onEvent(ProfilesUiEvent.DismissMessage) },
            title = { Text("Profiles") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { viewModel.onEvent(ProfilesUiEvent.DismissMessage) }) { Text("OK") } }
        )
    }

    state.pendingDeleteId?.let { id ->
        ConfirmDialog(
            title = "Delete profile?",
            message = "This also removes its bindings and overlay layout. This can't be undone.",
            confirmLabel = "Delete",
            onConfirm = { viewModel.onEvent(ProfilesUiEvent.ConfirmDelete(id)) },
            onDismiss = { viewModel.onEvent(ProfilesUiEvent.RequestDelete(null)) }
        )
    }

    if (showCreate) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("New profile") },
            text = { TextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.onEvent(ProfilesUiEvent.CreateProfile(name, null))
                    showCreate = false
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun ProfileRow(
    profile: MappingProfile,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit
) {
    com.titanmapper.core.ui.components.TitanCard(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(bottom = TitanSpacing.s)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TitanSpacing.s)) {
                androidx.compose.foundation.layout.Box(modifier = Modifier.size(12.dp).background(profile.colorTag.toColor(), CircleShape))
                Column {
                    Text(profile.name, style = MaterialTheme.typography.titleMedium)
                    val scope = buildString {
                        append(profile.targetPackageName ?: "Global")
                        if (profile.deviceFingerprintId != null) append(" · specific device")
                        append(" · ${profile.bindings.size} binding(s)")
                    }
                    Text(scope, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Switch(checked = profile.isActive, onCheckedChange = onToggle)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(TitanSpacing.xs)) {
            IconButton(onClick = onShare) { Icon(Icons.Filled.Share, contentDescription = "Share") }
            IconButton(onClick = onDuplicate) { Icon(Icons.Filled.ContentCopy, contentDescription = "Duplicate") }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
        }
    }
}

