package com.titanmapper.feature.mapping

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.titanmapper.core.common.DataResult
import com.titanmapper.core.data.repository.MacroRepository
import com.titanmapper.core.data.repository.ProfileRepository
import com.titanmapper.core.model.ActionMode
import com.titanmapper.core.model.Binding
import com.titanmapper.core.model.CaptureTier
import com.titanmapper.core.model.InputTrigger
import com.titanmapper.core.model.MacroDefinition
import com.titanmapper.core.model.MappedAction
import com.titanmapper.core.model.MappingProfile
import com.titanmapper.core.model.SensitivityPreset
import com.titanmapper.service.api.PluginInfo
import com.titanmapper.service.api.PluginRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class MappingEditorUiState(
    val profile: MappingProfile? = null,
    val macros: List<MacroDefinition> = emptyList(),
    val plugins: List<PluginInfo> = emptyList(),
    val editingBindingId: String? = null, // null = not editing; "" = creating new
    val saveError: String? = null
) {
    /** What the profile's chosen tier can actually perform — drives the warning chip in the action picker. */
    val tierCapabilities: TierCapability get() = TierCapability.of(profile?.permissionTier ?: CaptureTier.STANDARD)
}

enum class TierCapability {
    TOUCH_ONLY, TOUCH_AND_SYSTEM, FULL;

    fun supports(action: MappedAction): Boolean = when (this) {
        FULL -> true
        TOUCH_AND_SYSTEM -> action is MappedAction.TouchTap || action is MappedAction.TouchDrag ||
            action is MappedAction.SystemAction || action is MappedAction.MacroRun || action is MappedAction.NoAction
        TOUCH_ONLY -> action is MappedAction.TouchTap || action is MappedAction.TouchDrag ||
            action is MappedAction.MacroRun || action is MappedAction.NoAction
    }

    companion object {
        fun of(tier: CaptureTier): TierCapability = when (tier) {
            CaptureTier.STANDARD -> TOUCH_ONLY
            CaptureTier.ACCESSIBILITY -> TOUCH_AND_SYSTEM
            CaptureTier.SHIZUKU, CaptureTier.ROOT -> FULL
        }
    }
}

sealed interface MappingEditorUiEvent {
    data class Rename(val name: String) : MappingEditorUiEvent
    data class SetTargetPackage(val packageName: String?) : MappingEditorUiEvent
    data class SetDeviceFingerprint(val id: String?) : MappingEditorUiEvent
    data class SetPriority(val priority: Int) : MappingEditorUiEvent
    data class SetTier(val tier: CaptureTier) : MappingEditorUiEvent
    data class SetSensitivity(val preset: SensitivityPreset) : MappingEditorUiEvent
    data class BeginEditBinding(val bindingId: String?) : MappingEditorUiEvent // null closes, "" = new
    data class SaveBinding(val binding: Binding) : MappingEditorUiEvent
    data class DeleteBinding(val bindingId: String) : MappingEditorUiEvent
    data class ToggleBinding(val bindingId: String, val enabled: Boolean) : MappingEditorUiEvent
    data object DismissError : MappingEditorUiEvent
}

@HiltViewModel
class MappingEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: ProfileRepository,
    private val macroRepository: MacroRepository,
    private val pluginRegistry: PluginRegistry,
    inputEventStream: com.titanmapper.service.api.InputEventStream
) : ViewModel() {

    /** Same live stream the Diagnostics screen reads; the binding editor listens to it to learn which key/button was pressed. */
    val inputEvents: kotlinx.coroutines.flow.SharedFlow<com.titanmapper.core.model.RawInputEvent> = inputEventStream.events

    private val profileId: String = checkNotNull(savedStateHandle["profileId"])
    private val editingId = MutableStateFlow<String?>(null)
    private val saveError = MutableStateFlow<String?>(null)

    val uiState: StateFlow<MappingEditorUiState> = combine(
        repository.observeProfile(profileId), macroRepository.observeMacros(), pluginRegistry.plugins, editingId, saveError
    ) { profile, macros, plugins, editing, error ->
        MappingEditorUiState(profile, macros, plugins, editing, error)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MappingEditorUiState())

    init {
        pluginRegistry.refresh()
    }

    fun onEvent(event: MappingEditorUiEvent) {
        val current = uiState.value.profile ?: return
        when (event) {
            is MappingEditorUiEvent.Rename -> save(current.copy(name = event.name))
            is MappingEditorUiEvent.SetTargetPackage -> save(current.copy(targetPackageName = event.packageName))
            is MappingEditorUiEvent.SetDeviceFingerprint -> save(current.copy(deviceFingerprintId = event.id))
            is MappingEditorUiEvent.SetPriority -> save(current.copy(priority = event.priority))
            is MappingEditorUiEvent.SetTier -> save(current.copy(permissionTier = event.tier))
            is MappingEditorUiEvent.SetSensitivity -> save(current.copy(sensitivity = event.preset))
            is MappingEditorUiEvent.BeginEditBinding -> editingId.value = event.bindingId
            is MappingEditorUiEvent.SaveBinding -> {
                val exists = current.bindings.any { it.id == event.binding.id }
                val next = if (exists) current.bindings.map { if (it.id == event.binding.id) event.binding else it }
                else current.bindings + event.binding
                save(current.copy(bindings = next))
                editingId.value = null
            }
            is MappingEditorUiEvent.DeleteBinding -> save(current.copy(bindings = current.bindings.filterNot { it.id == event.bindingId }))
            is MappingEditorUiEvent.ToggleBinding -> save(
                current.copy(bindings = current.bindings.map { if (it.id == event.bindingId) it.copy(isEnabled = event.enabled) else it })
            )
            MappingEditorUiEvent.DismissError -> saveError.value = null
        }
    }

    /** Duplicate-trigger detection, exposed for the binding editor to warn before saving. */
    fun conflictingBindingName(trigger: InputTrigger, excludingId: String?): String? {
        val profile = uiState.value.profile ?: return null
        val conflict = profile.bindings.firstOrNull { it.id != excludingId && it.isEnabled && it.trigger == trigger }
        return conflict?.let { "Already bound (ID ${it.id.take(6)})" }
    }

    fun newBindingId(): String = UUID.randomUUID().toString()

    private fun save(profile: MappingProfile) {
        viewModelScope.launch {
            when (val result = repository.save(profile)) {
                is DataResult.Error -> saveError.value = result.message
                is DataResult.Success -> Unit
            }
        }
    }
}
