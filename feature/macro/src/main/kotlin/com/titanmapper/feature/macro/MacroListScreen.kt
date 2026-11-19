package com.titanmapper.feature.macro

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.titanmapper.core.model.MacroDefinition
import com.titanmapper.core.ui.components.EmptyState
import com.titanmapper.core.ui.theme.TitanSpacing
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue

@Composable
fun MacroListRoute(onEditMacro: (String) -> Unit, viewModel: MacroListViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exportText by remember { mutableStateOf<String?>(null) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()?.let {
                viewModel.onEvent(MacroListUiEvent.ImportFrom(it))
            }
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        val text = exportText
        if (uri != null && text != null) scope.launch { context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(text) } }
        exportText = null
    }

    var pendingNavigate by remember { mutableStateOf(false) }
    LaunchedEffect(pendingNavigate) {
        if (pendingNavigate) {
            viewModel.createdMacroId?.let(onEditMacro)
            pendingNavigate = false
        }
    }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { viewModel.onEvent(MacroListUiEvent.CreateMacro); pendingNavigate = true },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("New macro") }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(modifier = Modifier.fillMaxWidth().padding(TitanSpacing.l), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = { importLauncher.launch(arrayOf("application/json")) }) { Icon(Icons.Filled.Upload, contentDescription = "Import") }
            }
            if (state.macros.isEmpty()) {
                EmptyState(icon = Icons.Filled.Add, title = "No macros yet", message = "Record or build a sequence of key presses, clicks, and delays.")
            } else {
                LazyColumn(contentPadding = PaddingValues(horizontal = TitanSpacing.l)) {
                    items(state.macros, key = { it.id }) { macro ->
                        MacroRow(
                            macro = macro,
                            running = macro.id in state.running,
                            onClick = { onEditMacro(macro.id) },
                            onRun = { viewModel.onEvent(MacroListUiEvent.Run(macro.id)) },
                            onCancel = { viewModel.onEvent(MacroListUiEvent.Cancel(macro.id)) },
                            onDuplicate = { viewModel.onEvent(MacroListUiEvent.Duplicate(macro.id)) },
                            onDelete = { viewModel.onEvent(MacroListUiEvent.Delete(macro.id)) },
                            onShare = {
                                viewModel.onEvent(MacroListUiEvent.ExportOne(macro.id) { text ->
                                    exportText = text
                                    exportLauncher.launch("${macro.name}.titanmacro.json")
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
            onDismissRequest = { viewModel.onEvent(MacroListUiEvent.DismissMessage) },
            title = { Text("Macros") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { viewModel.onEvent(MacroListUiEvent.DismissMessage) }) { Text("OK") } }
        )
    }
}

@Composable
private fun MacroRow(
    macro: MacroDefinition, running: Boolean, onClick: () -> Unit, onRun: () -> Unit, onCancel: () -> Unit,
    onDuplicate: () -> Unit, onDelete: () -> Unit, onShare: () -> Unit
) {
    com.titanmapper.core.ui.components.TitanCard(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(bottom = TitanSpacing.s)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(macro.name, style = MaterialTheme.typography.titleMedium)
                Text("${com.titanmapper.core.model.MacroValidator.countSteps(macro.steps)} step(s)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = if (running) onCancel else onRun) {
                Icon(if (running) Icons.Filled.Stop else Icons.Filled.PlayArrow, contentDescription = if (running) "Stop" else "Run")
            }
        }
        Row {
            IconButton(onClick = onShare) { Icon(Icons.Filled.Share, contentDescription = "Share") }
            IconButton(onClick = onDuplicate) { Icon(Icons.Filled.ContentCopy, contentDescription = "Duplicate") }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
        }
    }
}
