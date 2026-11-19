package com.titanmapper.feature.devices

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
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.DeviceUnknown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.titanmapper.core.model.DeviceClass
import com.titanmapper.core.model.DeviceRecord
import com.titanmapper.core.ui.components.EmptyState
import com.titanmapper.core.ui.components.StatusChip
import com.titanmapper.core.ui.components.StatusKind
import com.titanmapper.core.ui.components.TitanCard
import com.titanmapper.core.ui.theme.TitanSpacing

@Composable
fun DevicesRoute(viewModel: DevicesViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    DevicesContent(state, viewModel::onEvent)
}

@Composable
private fun DevicesContent(state: DevicesUiState, onEvent: (DevicesUiEvent) -> Unit) {
    if (state.devices.isEmpty()) {
        EmptyState(
            icon = Icons.Filled.Keyboard,
            title = "No devices yet",
            message = "Connect a keyboard or mouse over USB-OTG, Bluetooth, or a wireless dongle to see it here."
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(TitanSpacing.l)
    ) {
        items(state.devices, key = { it.fingerprint.stableId }) { device ->
            DeviceRow(device, onRename = { onEvent(DevicesUiEvent.BeginRename(device.fingerprint.stableId)) }, onForget = { onEvent(DevicesUiEvent.Forget(device.fingerprint.stableId)) })
        }
    }
    val renamingId = state.renamingId
    if (renamingId != null) {
        val device = state.devices.firstOrNull { it.fingerprint.stableId == renamingId }
        var text by remember(renamingId) { mutableStateOf(device?.nickname ?: "") }
        AlertDialog(
            onDismissRequest = { onEvent(DevicesUiEvent.BeginRename(null)) },
            title = { Text("Rename device") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = { onEvent(DevicesUiEvent.ConfirmRename(renamingId, text)) }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { onEvent(DevicesUiEvent.BeginRename(null)) }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun DeviceRow(device: DeviceRecord, onRename: () -> Unit, onForget: () -> Unit) {
    TitanCard(modifier = Modifier.fillMaxWidth().padding(bottom = TitanSpacing.s)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TitanSpacing.s)) {
                Icon(iconFor(device.fingerprint.deviceClass), contentDescription = null)
                Column {
                    Text(device.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(device.fingerprint.deviceName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TitanSpacing.s)) {
                StatusChip(text = if (device.connected) "Connected" else "Offline", kind = if (device.connected) StatusKind.OK else StatusKind.NEUTRAL)
                IconButton(onClick = onRename) { Icon(androidx.compose.material.icons.Icons.Filled.Mouse, contentDescription = "Rename") }
            }
        }
        TextButton(onClick = onForget) { Text("Forget device") }
    }
}

private fun iconFor(deviceClass: DeviceClass) = when (deviceClass) {
    DeviceClass.KEYBOARD -> Icons.Filled.Keyboard
    DeviceClass.MOUSE -> Icons.Filled.Mouse
    DeviceClass.HYBRID -> Icons.Filled.Keyboard
    else -> Icons.Filled.DeviceUnknown
}
