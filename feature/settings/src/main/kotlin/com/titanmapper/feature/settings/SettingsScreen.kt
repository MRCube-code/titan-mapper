package com.titanmapper.feature.settings

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.titanmapper.core.model.PermissionTier
import com.titanmapper.core.model.ProfileColor
import com.titanmapper.core.model.ThemeMode
import com.titanmapper.core.ui.components.SectionHeader
import com.titanmapper.core.ui.components.TitanCard
import com.titanmapper.core.ui.format.displayName
import com.titanmapper.core.ui.theme.TitanSpacing
import com.titanmapper.core.ui.theme.toColor
import kotlinx.coroutines.launch

@Composable
fun SettingsRoute(viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingBackupBytes by remember { mutableStateOf<ByteArray?>(null) }
    var passphraseDialogFor by remember { mutableStateOf<String?>(null) } // "create" | "restore"

    val createDocLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        val bytes = pendingBackupBytes
        if (uri != null && bytes != null) {
            scope.launch { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } }
        }
        pendingBackupBytes = null
    }
    val openDocLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@launch
            if (viewModel.needsPassphrase(bytes)) {
                pendingBackupBytes = bytes
                passphraseDialogFor = "restore"
            } else {
                viewModel.onEvent(SettingsUiEvent.RestoreBackup(bytes, null))
            }
        }
    }

    SettingsContent(
        state = state,
        onEvent = viewModel::onEvent,
        onCreateBackup = { passphraseDialogFor = "create" },
        onRestoreBackup = { openDocLauncher.launch(arrayOf("application/json", "application/octet-stream")) }
    )

    val stage = passphraseDialogFor
    if (stage != null) {
        var passphrase by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { passphraseDialogFor = null; pendingBackupBytes = null },
            title = { Text(if (stage == "create") "Backup passphrase (optional)" else "Enter backup passphrase") },
            text = {
                OutlinedTextField(
                    value = passphrase, onValueChange = { passphrase = it },
                    label = { Text("Passphrase") }, singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (stage == "create") {
                        viewModel.onEvent(SettingsUiEvent.CreateBackup(passphrase.ifBlank { null }) { bytes ->
                            pendingBackupBytes = bytes
                            createDocLauncher.launch("titanmapper-backup.json")
                        })
                    } else {
                        pendingBackupBytes?.let { viewModel.onEvent(SettingsUiEvent.RestoreBackup(it, passphrase.ifBlank { null })) }
                    }
                    passphraseDialogFor = null
                }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { passphraseDialogFor = null; pendingBackupBytes = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun SettingsContent(
    state: SettingsUiState,
    onEvent: (SettingsUiEvent) -> Unit,
    onCreateBackup: () -> Unit,
    onRestoreBackup: () -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(TitanSpacing.l), verticalArrangement = Arrangement.spacedBy(TitanSpacing.m)) {
        item { PermissionsSection() }
        item { ThemeSection(state, onEvent) }
        item { BehaviorSection(state, onEvent) }
        item { BackupSection(state, onCreateBackup, onRestoreBackup) }
        item { PluginSection(state, onEvent) }
        item { UpdateSection(state, onEvent) }
        item { AboutSection() }
    }
}

/**
 * Entry points into the two XML/ViewBinding "system" screens that match the reference
 * screenshots exactly (see [com.titanmapper.app.PermissionsManagementActivity]/
 * [com.titanmapper.app.ShizukuSetupActivity] in :app, which this module can't depend on directly
 * since the dependency runs the other way -- so these are launched by fully-qualified class name
 * instead of a compile-time reference).
 */
@Composable
private fun PermissionsSection() {
    val context = LocalContext.current
    TitanCard {
        SectionHeader("Permissions & Shizuku")
        Text(
            "Check and grant the overlay, background-run, and Shizuku permissions this app needs.",
            style = MaterialTheme.typography.bodySmall
        )
        Row(horizontalArrangement = Arrangement.spacedBy(TitanSpacing.s)) {
            Button(onClick = {
                context.startActivity(android.content.Intent().setClassName(context, "com.titanmapper.app.PermissionsManagementActivity"))
            }) { Text("Permissions") }
            TextButton(onClick = {
                context.startActivity(android.content.Intent().setClassName(context, "com.titanmapper.app.ShizukuSetupActivity"))
            }) { Text("Shizuku setup") }
        }
    }
}

@Composable
private fun ThemeSection(state: SettingsUiState, onEvent: (SettingsUiEvent) -> Unit) {
    TitanCard {
        SectionHeader("Appearance")
        Row(horizontalArrangement = Arrangement.spacedBy(TitanSpacing.s)) {
            ThemeMode.entries.forEach { mode ->
                FilterChip(selected = state.settings.theme.mode == mode, onClick = { onEvent(SettingsUiEvent.SetThemeMode(mode)) }, label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) })
            }
        }
        LabeledSwitch("Dynamic color (Material You)", state.settings.theme.useDynamicColor) { onEvent(SettingsUiEvent.SetDynamicColor(it)) }
        if (!state.settings.theme.useDynamicColor) {
            Row(horizontalArrangement = Arrangement.spacedBy(TitanSpacing.xs)) {
                ProfileColor.entries.forEach { color ->
                    androidx.compose.material3.IconButton(onClick = { onEvent(SettingsUiEvent.SetAccent(color)) }) {
                        androidx.compose.foundation.Canvas(modifier = Modifier.padding(2.dp).size(24.dp)) {
                            drawCircle(color = color.toColor(), radius = size.minDimension / 2)
                        }
                    }
                }
            }
        }
        LabeledSwitch("Compact layout when a mouse is present", state.settings.theme.compactWhenPointerPresent) { onEvent(SettingsUiEvent.SetCompact(it)) }
    }
}

@Composable
private fun BehaviorSection(state: SettingsUiState, onEvent: (SettingsUiEvent) -> Unit) {
    TitanCard {
        SectionHeader("Behavior")
        Text("Preferred permission mode", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(TitanSpacing.s)) {
            PermissionTier.entries.forEach { tier ->
                FilterChip(selected = state.settings.preferredTier == tier, onClick = { onEvent(SettingsUiEvent.SetPreferredTier(tier)) }, label = { Text(tier.displayName()) })
            }
        }
        LabeledSwitch("Automatic profile switching", state.settings.autoProfileSwitch) { onEvent(SettingsUiEvent.SetAutoProfileSwitch(it)) }
        LabeledSwitch("Start service on boot", state.settings.startServiceOnBoot) { onEvent(SettingsUiEvent.SetStartOnBoot(it)) }
    }
}

@Composable
private fun BackupSection(state: SettingsUiState, onCreateBackup: () -> Unit, onRestoreBackup: () -> Unit) {
    TitanCard {
        SectionHeader("Backup")
        Text("Save or restore every profile, macro and overlay layout as one file. Add a passphrase to encrypt it.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(TitanSpacing.s)) {
            Button(onClick = onCreateBackup, enabled = !state.backupBusy) { Text("Create backup") }
            TextButton(onClick = onRestoreBackup, enabled = !state.backupBusy) { Text("Restore backup") }
        }
        state.backupMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
    }
}

@Composable
private fun PluginSection(state: SettingsUiState, onEvent: (SettingsUiEvent) -> Unit) {
    TitanCard {
        SectionHeader("Plugins")
        if (state.plugins.isEmpty()) {
            Text("No plugins installed.", style = MaterialTheme.typography.bodySmall)
        } else {
            state.plugins.forEach { plugin ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text(plugin.label, style = MaterialTheme.typography.bodyMedium)
                        Text("${plugin.actions.size} action(s)", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = plugin.enabled, onCheckedChange = { onEvent(SettingsUiEvent.TogglePlugin(plugin.packageName, it)) })
                }
            }
        }
        TextButton(onClick = { onEvent(SettingsUiEvent.RefreshPlugins) }) { Text("Refresh") }
    }
}

@Composable
private fun UpdateSection(state: SettingsUiState, onEvent: (SettingsUiEvent) -> Unit) {
    TitanCard {
        SectionHeader("Updates")
        OutlinedTextField(
            value = state.settings.updateChannelUrl,
            onValueChange = { onEvent(SettingsUiEvent.SetUpdateUrl(it)) },
            label = { Text("Update manifest URL (https)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = { onEvent(SettingsUiEvent.CheckForUpdates) }) { Text("Check for updates") }
    }
}

@Composable
private fun AboutSection() {
    TitanCard {
        SectionHeader("About")
        Text("Titan Mapper", style = MaterialTheme.typography.titleMedium)
        Text("An advanced keyboard and mouse mapping platform for Android.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun LabeledSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = TitanSpacing.s))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
