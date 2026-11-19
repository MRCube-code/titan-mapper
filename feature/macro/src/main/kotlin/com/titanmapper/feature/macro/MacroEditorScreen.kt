package com.titanmapper.feature.macro

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.titanmapper.core.model.KeyCode
import com.titanmapper.core.model.MacroStep
import com.titanmapper.core.model.MouseButton
import com.titanmapper.core.ui.theme.TitanSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MacroEditorRoute(
    macroId: String,
    onBack: () -> Unit,
    viewModel: MacroEditorViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showAddStep by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    OutlinedTextField(
                        value = state.macro?.name ?: "",
                        onValueChange = { viewModel.onEvent(MacroEditorUiEvent.Rename(it)) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.titleMedium,
                        label = { Text("Macro name") },
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.onEvent(MacroEditorUiEvent.Save) },
                        enabled = state.isDirty && state.validationErrors.isEmpty()
                    ) {
                        Icon(Icons.Filled.Save, "Save")
                    }
                }
            )
        }
    ) { padding ->
        val macro = state.macro
        if (macro == null) {
            Column(modifier = Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Loading…", modifier = Modifier.padding(TitanSpacing.l))
            }
            return@Scaffold
        }

        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Global toggle
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = TitanSpacing.l),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = macro.isGlobal,
                    onCheckedChange = { viewModel.onEvent(MacroEditorUiEvent.SetGlobal(it)) }
                )
                Spacer(Modifier.width(TitanSpacing.s))
                Text("Available in all profiles", style = MaterialTheme.typography.bodyMedium)
            }

            // Recorder panel
            RecorderPanel(
                recording = state.recorder.recording,
                capturedCount = state.recorder.capturedEvents,
                onStart = { viewModel.onEvent(MacroEditorUiEvent.StartRecording) },
                onStop = { viewModel.onEvent(MacroEditorUiEvent.StopRecording) }
            )

            // Validation errors
            if (state.validationErrors.isNotEmpty()) {
                Column(modifier = Modifier.padding(horizontal = TitanSpacing.l)) {
                    state.validationErrors.forEach { err ->
                        Text(err, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            // Step count
            Text(
                "${macro.steps.size} step(s)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = TitanSpacing.l)
            )

            // Step list
            LazyColumn(
                contentPadding = PaddingValues(horizontal = TitanSpacing.l),
                modifier = Modifier.weight(1f)
            ) {
                itemsIndexed(macro.steps, key = { idx, _ -> idx }) { idx, step ->
                    StepRow(
                        index = idx,
                        step = step,
                        onDelete = { viewModel.onEvent(MacroEditorUiEvent.RemoveStep(idx)) },
                        onUpdateDelay = { millis ->
                            viewModel.onEvent(MacroEditorUiEvent.UpdateStep(idx, MacroStep.Delay(millis)))
                        }
                    )
                }
            }

            // Add step button
            Row(
                modifier = Modifier.fillMaxWidth().padding(TitanSpacing.l),
                horizontalArrangement = Arrangement.Center
            ) {
                Button(onClick = { showAddStep = true }) {
                    Icon(Icons.Filled.Add, null)
                    Spacer(Modifier.width(TitanSpacing.s))
                    Text("Add step")
                }
            }
        }
    }

    if (showAddStep) {
        AddStepDialog(
            onDismiss = { showAddStep = false },
            onAdd = { step ->
                viewModel.onEvent(MacroEditorUiEvent.AddStep(step))
                showAddStep = false
            }
        )
    }

    state.saveError?.let { err ->
        AlertDialog(
            onDismissRequest = { viewModel.onEvent(MacroEditorUiEvent.DismissError) },
            title = { Text("Save failed") },
            text = { Text(err) },
            confirmButton = {
                TextButton(onClick = { viewModel.onEvent(MacroEditorUiEvent.DismissError) }) { Text("OK") }
            }
        )
    }
}

@Composable
private fun RecorderPanel(
    recording: Boolean,
    capturedCount: Int,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = TitanSpacing.l, vertical = TitanSpacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TitanSpacing.s)
    ) {
        if (recording) {
            Button(
                onClick = onStop,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Filled.Stop, null)
                Spacer(Modifier.width(TitanSpacing.xs))
                Text("Stop ($capturedCount events)")
            }
        } else {
            Button(onClick = onStart) {
                Icon(Icons.Filled.FiberManualRecord, null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(TitanSpacing.xs))
                Text("Record")
            }
        }
        Text(
            if (recording) "Recording… press keys/buttons on your device" else "Record a sequence of inputs automatically",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun StepRow(
    index: Int,
    step: MacroStep,
    onDelete: () -> Unit,
    onUpdateDelay: (Long) -> Unit
) {
    com.titanmapper.core.ui.components.TitanCard(
        modifier = Modifier.fillMaxWidth().padding(bottom = TitanSpacing.xs)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${index + 1}. ${step.label()}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            if (step is MacroStep.Delay) {
                var text by remember(step.millis) { mutableStateOf(step.millis.toString()) }
                OutlinedTextField(
                    value = text,
                    onValueChange = { v ->
                        text = v
                        v.toLongOrNull()?.let(onUpdateDelay)
                    },
                    suffix = { Text("ms") },
                    singleLine = true,
                    modifier = Modifier.width(100.dp)
                )
            }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Delete step") }
        }
    }
}

/** Human-readable label for a [MacroStep]. */
private fun MacroStep.label(): String = when (this) {
    is MacroStep.PressKey -> "Press ${key.name}"
    is MacroStep.ReleaseKey -> "Release ${key.name}"
    is MacroStep.ClickMouse -> "Click ${button.name}"
    is MacroStep.MoveMouse -> "Move mouse (Δx=${deltaX}, Δy=${deltaY})"
    is MacroStep.Delay -> "Wait ${millis} ms"
    is MacroStep.LoopStart -> "Loop start (${count ?: "∞"}×)"
    MacroStep.LoopEnd -> "Loop end"
    is MacroStep.ConditionalIf -> "If ${condition.javaClass.simpleName}"
}

@Composable
private fun AddStepDialog(onDismiss: () -> Unit, onAdd: (MacroStep) -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add step") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(TitanSpacing.s)) {
                // Quick shortcuts for the most common steps
                Text("Common steps:", style = MaterialTheme.typography.labelMedium)

                FilterChip(selected = false, onClick = { onAdd(MacroStep.Delay(100)) }, label = { Text("Delay 100 ms") })
                FilterChip(selected = false, onClick = { onAdd(MacroStep.PressKey(KeyCode.A)) }, label = { Text("Press key (A)") })
                FilterChip(selected = false, onClick = { onAdd(MacroStep.ReleaseKey(KeyCode.A)) }, label = { Text("Release key (A)") })
                FilterChip(selected = false, onClick = { onAdd(MacroStep.ClickMouse(MouseButton.LEFT)) }, label = { Text("Click Left Mouse") })
                FilterChip(selected = false, onClick = { onAdd(MacroStep.MoveMouse(0f, 0f)) }, label = { Text("Move mouse") })
                FilterChip(selected = false, onClick = { onAdd(MacroStep.LoopStart(5)) }, label = { Text("Loop start (5×)") })
                FilterChip(selected = false, onClick = { onAdd(MacroStep.LoopEnd) }, label = { Text("Loop end") })
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
