package com.titanmapper.feature.mapping

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.titanmapper.core.model.ActionMode
import com.titanmapper.core.model.Binding
import com.titanmapper.core.model.CaptureTier
import com.titanmapper.core.model.InputTrigger
import com.titanmapper.core.ui.components.EmptyState
import com.titanmapper.core.ui.components.LabeledSlider
import com.titanmapper.core.ui.components.StatusChip
import com.titanmapper.core.ui.components.StatusKind
import com.titanmapper.core.ui.components.TitanCard
import com.titanmapper.core.ui.format.describe
import com.titanmapper.core.ui.format.displayName
import com.titanmapper.core.ui.theme.TitanSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MappingEditorRoute(
    profileId: String,
    onBack: () -> Unit,
    onEditOverlay: (String) -> Unit,
    viewModel: MappingEditorViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val profile = state.profile

    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(profile?.name ?: "Profile") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    IconButton(onClick = { onEditOverlay(profileId) }) { Icon(Icons.Filled.Tune, contentDescription = "Overlay editor") }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { viewModel.onEvent(MappingEditorUiEvent.BeginEditBinding("")) },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("Add binding") }
            )
        }
    ) { padding ->
        if (profile == null) {
            Column(modifier = Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.Center) {
                Text("Loading…", modifier = Modifier.padding(TitanSpacing.l))
            }
            return@Scaffold
        }
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(TitanSpacing.l), verticalArrangement = Arrangement.spacedBy(TitanSpacing.m)) {
            item { ProfileSettingsCard(profile.priority, profile.permissionTier) { viewModel.onEvent(it) } }
            item { SensitivityCard(profile.sensitivity) { viewModel.onEvent(MappingEditorUiEvent.SetSensitivity(it)) } }
            item { Text("Bindings (${profile.bindings.size})", style = MaterialTheme.typography.titleMedium) }
            if (profile.bindings.isEmpty()) {
                item { EmptyState(icon = Icons.Filled.Add, title = "No bindings yet", message = "Tap “Add binding” to map a key, button, or scroll action.") }
            } else {
                items(profile.bindings, key = { it.id }) { binding ->
                    BindingRow(
                        binding = binding,
                        supported = state.tierCapabilities.supports(binding.action),
                        onClick = { viewModel.onEvent(MappingEditorUiEvent.BeginEditBinding(binding.id)) },
                        onToggle = { viewModel.onEvent(MappingEditorUiEvent.ToggleBinding(binding.id, it)) },
                        onDelete = { viewModel.onEvent(MappingEditorUiEvent.DeleteBinding(binding.id)) }
                    )
                }
            }
        }
    }

    val editingId = state.editingBindingId
    if (editingId != null && profile != null) {
        val existing = profile.bindings.firstOrNull { it.id == editingId }
        BindingEditorSheet(
            existing = existing,
            tierCapability = state.tierCapabilities,
            macros = state.macros,
            plugins = state.plugins,
            inputEvents = viewModel.inputEvents,
            newId = { viewModel.newBindingId() },
            conflictCheck = { trigger -> viewModel.conflictingBindingName(trigger, existing?.id) },
            onSave = { viewModel.onEvent(MappingEditorUiEvent.SaveBinding(it)) },
            onDismiss = { viewModel.onEvent(MappingEditorUiEvent.BeginEditBinding(null)) }
        )
    }
    }
}

@Composable
private fun ProfileSettingsCard(priority: Int, tier: CaptureTier, onEvent: (MappingEditorUiEvent) -> Unit) {
    TitanCard {
        Text("Profile settings", style = MaterialTheme.typography.titleMedium)
        Text("Permission tier", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(TitanSpacing.s)) {
            CaptureTier.entries.forEach { t ->
                FilterChip(selected = tier == t, onClick = { onEvent(MappingEditorUiEvent.SetTier(t)) }, label = { Text(t.displayName()) })
            }
        }
        LabeledSlider(
            label = "Priority (for auto profile switching)",
            value = priority.toFloat(),
            onValueChange = { onEvent(MappingEditorUiEvent.SetPriority(it.toInt())) },
            valueRange = 0f..100f,
            valueText = priority.toString()
        )
    }
}

@Composable
private fun SensitivityCard(preset: com.titanmapper.core.model.SensitivityPreset, onChange: (com.titanmapper.core.model.SensitivityPreset) -> Unit) {
    TitanCard {
        Text("Mouse sensitivity", style = MaterialTheme.typography.titleMedium)
        SensitivityCurvePreview(preset)
        LabeledSlider("X sensitivity", preset.xScale, { onChange(preset.copy(xScale = it)) }, 0.1f..3f, "%.2f".format(preset.xScale))
        LabeledSlider("Y sensitivity", preset.yScale, { onChange(preset.copy(yScale = it)) }, 0.1f..3f, "%.2f".format(preset.yScale))
        LabeledSlider("Acceleration", preset.accelerationExponent, { onChange(preset.copy(accelerationExponent = it)) }, 1f..3f, "%.2f".format(preset.accelerationExponent))
        Row(horizontalArrangement = Arrangement.spacedBy(TitanSpacing.s)) {
            com.titanmapper.core.model.PointerMode.entries.forEach { mode ->
                FilterChip(selected = preset.mode == mode, onClick = { onChange(preset.copy(mode = mode)) }, label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = preset.rawInputEnabled, onCheckedChange = { onChange(preset.copy(rawInputEnabled = it)) })
            Text("Raw input (bypass acceleration curve)", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun BindingRow(binding: Binding, supported: Boolean, onClick: () -> Unit, onToggle: (Boolean) -> Unit, onDelete: () -> Unit) {
    TitanCard(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(bottom = TitanSpacing.s)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(binding.trigger.describe(), style = MaterialTheme.typography.titleSmall)
                Text("${binding.mode.displayName()} → ${binding.action.describe()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!supported) {
                    StatusChip(text = "Not supported by this profile's tier", kind = StatusKind.WARN, modifier = Modifier.padding(top = TitanSpacing.xs))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = binding.isEnabled, onCheckedChange = onToggle)
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
            }
        }
    }
}
