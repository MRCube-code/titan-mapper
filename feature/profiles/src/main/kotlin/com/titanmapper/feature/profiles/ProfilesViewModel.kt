package com.titanmapper.feature.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.titanmapper.core.common.DataResult
import com.titanmapper.core.data.repository.ProfileRepository
import com.titanmapper.core.data.transfer.ProfileTransfer
import com.titanmapper.core.model.MappingProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class ProfilesUiState(
    val profiles: List<MappingProfile> = emptyList(),
    val query: String = "",
    val message: String? = null,
    val pendingDeleteId: String? = null
) {
    val filtered: List<MappingProfile> get() =
        if (query.isBlank()) profiles else profiles.filter { it.name.contains(query, ignoreCase = true) }
}

sealed interface ProfilesUiEvent {
    data class SetQuery(val query: String) : ProfilesUiEvent
    data class ToggleEnabled(val id: String, val enabled: Boolean) : ProfilesUiEvent
    data class CreateProfile(val name: String, val targetPackage: String?) : ProfilesUiEvent
    data class Duplicate(val id: String) : ProfilesUiEvent
    data class RequestDelete(val id: String?) : ProfilesUiEvent
    data class ConfirmDelete(val id: String) : ProfilesUiEvent
    data class ExportOne(val id: String, val writeText: suspend (String) -> Unit) : ProfilesUiEvent
    data class ImportFrom(val text: String) : ProfilesUiEvent
    data object DismissMessage : ProfilesUiEvent
}

@HiltViewModel
class ProfilesViewModel @Inject constructor(
    private val repository: ProfileRepository,
    private val transfer: ProfileTransfer
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val message = MutableStateFlow<String?>(null)
    private val pendingDelete = MutableStateFlow<String?>(null)

    val uiState: StateFlow<ProfilesUiState> = combine(
        repository.observeProfiles(), query, message, pendingDelete
    ) { profiles, q, msg, pending -> ProfilesUiState(profiles, q, msg, pending) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProfilesUiState())

    fun onEvent(event: ProfilesUiEvent) {
        when (event) {
            is ProfilesUiEvent.SetQuery -> query.value = event.query
            is ProfilesUiEvent.ToggleEnabled -> viewModelScope.launch { repository.setEnabled(event.id, event.enabled) }
            is ProfilesUiEvent.CreateProfile -> viewModelScope.launch {
                val profile = MappingProfile(id = UUID.randomUUID().toString(), name = event.name.ifBlank { "New profile" }, targetPackageName = event.targetPackage)
                when (val result = repository.save(profile)) {
                    is DataResult.Error -> message.value = result.message
                    else -> Unit
                }
            }
            is ProfilesUiEvent.Duplicate -> viewModelScope.launch {
                when (val result = repository.duplicate(event.id)) {
                    is DataResult.Error -> message.value = result.message
                    is DataResult.Success -> Unit
                }
            }
            is ProfilesUiEvent.RequestDelete -> pendingDelete.value = event.id
            is ProfilesUiEvent.ConfirmDelete -> viewModelScope.launch {
                repository.delete(event.id)
                pendingDelete.value = null
            }
            is ProfilesUiEvent.ExportOne -> viewModelScope.launch {
                val export = transfer.buildExport(setOf(event.id))
                event.writeText(transfer.encode(export))
            }
            is ProfilesUiEvent.ImportFrom -> viewModelScope.launch {
                when (val decoded = transfer.decode(event.text)) {
                    is DataResult.Error -> message.value = decoded.message
                    is DataResult.Success -> when (val imported = transfer.import(decoded.value)) {
                        is DataResult.Error -> message.value = imported.message
                        is DataResult.Success -> message.value =
                            "Imported ${imported.value.profiles} profile(s), ${imported.value.macros} macro(s). They start disabled."
                    }
                }
            }
            ProfilesUiEvent.DismissMessage -> message.value = null
        }
    }
}
