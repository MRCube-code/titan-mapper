package com.titanmapper.feature.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.titanmapper.core.data.repository.DeviceRepository
import com.titanmapper.core.model.DeviceRecord
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DevicesUiState(
    val devices: List<DeviceRecord> = emptyList(),
    val renamingId: String? = null
)

sealed interface DevicesUiEvent {
    data class BeginRename(val stableId: String?) : DevicesUiEvent
    data class ConfirmRename(val stableId: String, val nickname: String) : DevicesUiEvent
    data class Forget(val stableId: String) : DevicesUiEvent
}

@HiltViewModel
class DevicesViewModel @Inject constructor(
    private val repository: DeviceRepository
) : ViewModel() {

    private val renaming = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    val uiState: StateFlow<DevicesUiState> = kotlinx.coroutines.flow.combine(
        repository.observeDevices(), renaming
    ) { devices, renamingId -> DevicesUiState(devices, renamingId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DevicesUiState())

    fun onEvent(event: DevicesUiEvent) {
        when (event) {
            is DevicesUiEvent.BeginRename -> renaming.value = event.stableId
            is DevicesUiEvent.ConfirmRename -> viewModelScope.launch {
                repository.setNickname(event.stableId, event.nickname)
                renaming.value = null
            }
            is DevicesUiEvent.Forget -> viewModelScope.launch { repository.forget(event.stableId) }
        }
    }
}
