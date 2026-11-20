package com.titanmapper.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.titanmapper.core.common.DataResult
import com.titanmapper.core.data.backup.BackupManager
import com.titanmapper.core.data.settings.SettingsRepository
import com.titanmapper.core.model.AppSettings
import com.titanmapper.core.model.PermissionTier
import com.titanmapper.core.model.ProfileColor
import com.titanmapper.core.model.ThemeMode
import com.titanmapper.service.api.PluginInfo
import com.titanmapper.service.api.PluginRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val plugins: List<PluginInfo> = emptyList(),
    val backupBusy: Boolean = false,
    val backupMessage: String? = null,
    val pendingEncryptedRestoreBytes: ByteArray? = null,
    val updateMessage: String? = null
)

sealed interface SettingsUiEvent {
    data class SetThemeMode(val mode: ThemeMode) : SettingsUiEvent
    data class SetDynamicColor(val enabled: Boolean) : SettingsUiEvent
    data class SetAccent(val color: ProfileColor) : SettingsUiEvent
    data class SetCompact(val enabled: Boolean) : SettingsUiEvent
    data class SetPreferredTier(val tier: PermissionTier) : SettingsUiEvent
    data class SetAutoProfileSwitch(val enabled: Boolean) : SettingsUiEvent
    data class SetLogCapacity(val capacity: Int) : SettingsUiEvent
    data class SetIdleTeardown(val minutes: Int) : SettingsUiEvent
    data class SetStartOnBoot(val enabled: Boolean) : SettingsUiEvent
    data class SetUpdateUrl(val url: String) : SettingsUiEvent
    data class TogglePlugin(val packageName: String, val enabled: Boolean) : SettingsUiEvent
    data object RefreshPlugins : SettingsUiEvent
    data class CreateBackup(val passphrase: String?, val writeBytes: suspend (ByteArray) -> Unit) : SettingsUiEvent
    data class RestoreBackup(val bytes: ByteArray, val passphrase: String?) : SettingsUiEvent
    data object CheckForUpdates : SettingsUiEvent
    data object DismissMessage : SettingsUiEvent
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val pluginRegistry: PluginRegistry,
    private val backupManager: BackupManager,
    private val updateChecker: UpdateChecker
) : ViewModel() {

    private val backupBusy = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepository.settings, pluginRegistry.plugins, backupBusy, message
    ) { settings, plugins, busy, msg ->
        SettingsUiState(settings = settings, plugins = plugins, backupBusy = busy, backupMessage = msg)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    init {
        pluginRegistry.refresh()
    }

    fun onEvent(event: SettingsUiEvent) {
        when (event) {
            is SettingsUiEvent.SetThemeMode -> update { it.copy(theme = it.theme.copy(mode = event.mode)) }
            is SettingsUiEvent.SetDynamicColor -> update { it.copy(theme = it.theme.copy(useDynamicColor = event.enabled)) }
            is SettingsUiEvent.SetAccent -> update { it.copy(theme = it.theme.copy(accent = event.color)) }
            is SettingsUiEvent.SetCompact -> update { it.copy(theme = it.theme.copy(compactWhenPointerPresent = event.enabled)) }
            is SettingsUiEvent.SetPreferredTier -> update { it.copy(preferredTier = event.tier) }
            is SettingsUiEvent.SetAutoProfileSwitch -> update { it.copy(autoProfileSwitch = event.enabled) }
            is SettingsUiEvent.SetLogCapacity -> update { it.copy(eventLogCapacity = event.capacity) }
            is SettingsUiEvent.SetIdleTeardown -> update { it.copy(idleTeardownMinutes = event.minutes) }
            is SettingsUiEvent.SetStartOnBoot -> update { it.copy(startServiceOnBoot = event.enabled) }
            is SettingsUiEvent.SetUpdateUrl -> update { it.copy(updateChannelUrl = event.url) }
            is SettingsUiEvent.TogglePlugin -> pluginRegistry.setEnabled(event.packageName, event.enabled)
            SettingsUiEvent.RefreshPlugins -> pluginRegistry.refresh()
            is SettingsUiEvent.CreateBackup -> viewModelScope.launch {
                backupBusy.value = true
                val passphrase = event.passphrase?.takeIf { it.isNotBlank() }?.toCharArray()
                when (val result = backupManager.createBackup(passphrase)) {
                    is DataResult.Success -> {
                        event.writeBytes(result.value)
                        message.value = "Backup created"
                    }
                    is DataResult.Error -> message.value = result.message
                }
                backupBusy.value = false
            }
            is SettingsUiEvent.RestoreBackup -> viewModelScope.launch {
                backupBusy.value = true
                val passphrase = event.passphrase?.takeIf { it.isNotBlank() }?.toCharArray()
                when (val result = backupManager.restore(event.bytes, passphrase)) {
                    is DataResult.Success -> message.value =
                        "Restored ${result.value.profiles} profile(s), ${result.value.macros} macro(s)"
                    is DataResult.Error -> message.value = result.message
                }
                backupBusy.value = false
            }
            SettingsUiEvent.CheckForUpdates -> viewModelScope.launch {
                val url = uiState.value.settings.updateChannelUrl
                when (val result = updateChecker.check(url, installedVersionCode = BuildConfigVersion.CODE)) {
                    is DataResult.Success -> message.value = result.value?.let {
                        "Update available: ${it.versionName}\n${it.notes}"
                    } ?: "You're up to date"
                    is DataResult.Error -> message.value = result.message
                }
            }
            SettingsUiEvent.DismissMessage -> message.value = null
        }
    }

    fun needsPassphrase(bytes: ByteArray): Boolean = backupManager.needsPassphrase(bytes)

    private fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { settingsRepository.update(transform) }
    }
}

/** Avoids a direct :app BuildConfig dependency from a feature module; :app supplies the real value via DI if needed. */
internal object BuildConfigVersion {
    const val CODE = 1
}
