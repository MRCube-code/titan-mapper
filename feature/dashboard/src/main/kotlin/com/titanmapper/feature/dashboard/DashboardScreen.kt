package com.titanmapper.feature.dashboard

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
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.titanmapper.core.model.CapabilitySnapshot
import com.titanmapper.core.model.PermissionTier
import com.titanmapper.core.ui.components.StatusChip
import com.titanmapper.core.ui.components.StatusKind
import com.titanmapper.core.ui.components.TitanCard
import com.titanmapper.core.ui.format.displayName
import com.titanmapper.core.ui.format.formatDuration
import com.titanmapper.core.ui.format.formatMicros
import com.titanmapper.core.ui.format.summary
import com.titanmapper.core.ui.theme.TitanSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardRoute(
    onOpenDevices: () -> Unit,
    onOpenProfiles: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    DashboardContent(
        state = state,
        onEvent = viewModel::onEvent,
        onOpenDevices = onOpenDevices,
        onOpenProfiles = onOpenProfiles,
        onOpenDiagnostics = onOpenDiagnostics,
        onOpenSettings = onOpenSettings
    )
}

@Composable
private fun DashboardContent(
    state: DashboardUiState,
    onEvent: (DashboardUiEvent) -> Unit,
    onOpenDevices: () -> Unit,
    onOpenProfiles: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenSettings: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(TitanSpacing.l),
        verticalArrangement = Arrangement.spacedBy(TitanSpacing.m)
    ) {
        item { ServiceCard(state, onEvent) }
        item { TierPicker(state.capabilities, state.service.requestedTier, onEvent) }
        item { StatsRow(state) }
        item { ShortcutsRow(onOpenDevices, onOpenProfiles, onOpenDiagnostics, onOpenSettings) }
    }
}

@Composable
private fun ServiceCard(state: DashboardUiState, onEvent: (DashboardUiEvent) -> Unit) {
    TitanCard {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Remapping", style = MaterialTheme.typography.titleMedium)
                Text(
                    state.service.activeProfileName ?: "No active profile",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = state.service.running, onCheckedChange = { onEvent(DashboardUiEvent.ToggleService) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(TitanSpacing.s)) {
            StatusChip(
                text = if (state.service.running) "Running · ${state.service.activeTier.displayName()}" else "Stopped",
                kind = if (state.service.running) StatusKind.OK else StatusKind.NEUTRAL
            )
            if (state.service.activeTier != state.service.requestedTier && state.service.running) {
                StatusChip(text = "Downgraded from ${state.service.requestedTier.displayName()}", kind = StatusKind.WARN)
            }
        }
        state.service.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun TierPicker(capabilities: CapabilitySnapshot, selected: PermissionTier, onEvent: (DashboardUiEvent) -> Unit) {
    TitanCard {
        Text("Permission mode", style = MaterialTheme.typography.titleMedium)
        PermissionTier.entries.forEach { tier ->
            val available = capabilities.isAvailable(tier)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = selected == tier,
                    onClick = { onEvent(DashboardUiEvent.SelectTier(tier)) },
                    label = { Text(tier.displayName()) },
                    enabled = available || tier == PermissionTier.STANDARD
                )
                StatusChip(
                    text = if (available) "Available" else "Not available",
                    kind = if (available) StatusKind.OK else StatusKind.NEUTRAL
                )
            }
            Text(
                tier.summary(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = TitanSpacing.s)
            )
        }
        Button(onClick = { onEvent(DashboardUiEvent.RefreshCapabilities) }) { Text("Re-check permissions") }
    }
}

@Composable
private fun StatsRow(state: DashboardUiState) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(TitanSpacing.m)) {
        TitanCard(modifier = Modifier.weight(1f)) {
            Text("Latency", style = MaterialTheme.typography.labelLarge)
            Text(formatMicros(state.latency.averageMicros), style = MaterialTheme.typography.headlineSmall)
            Text("p95 ${formatMicros(state.latency.p95Micros)}", style = MaterialTheme.typography.bodySmall)
        }
        TitanCard(modifier = Modifier.weight(1f)) {
            Text("Events/s", style = MaterialTheme.typography.labelLarge)
            Text("${state.performance.eventsPerSecond}", style = MaterialTheme.typography.headlineSmall)
            Text("Uptime ${formatDuration(state.performance.uptimeMillis)}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ShortcutsRow(onOpenDevices: () -> Unit, onOpenProfiles: () -> Unit, onOpenDiagnostics: () -> Unit, onOpenSettings: () -> Unit) {
    data class Shortcut(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val onClick: () -> Unit)
    val shortcuts = listOf(
        Shortcut("Devices", Icons.Filled.DeveloperBoard, onOpenDevices),
        Shortcut("Profiles", Icons.Filled.Tune, onOpenProfiles),
        Shortcut("Diagnostics", Icons.Filled.BugReport, onOpenDiagnostics),
        Shortcut("Settings", Icons.Filled.Settings, onOpenSettings)
    )
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(TitanSpacing.s)) {
        shortcuts.forEach { s ->
            TitanCard(modifier = Modifier.weight(1f), onClick = s.onClick) {
                Icon(s.icon, contentDescription = null)
                Text(s.label, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
