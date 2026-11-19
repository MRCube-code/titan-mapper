package com.titanmapper.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.titanmapper.core.model.CapabilitySnapshot
import com.titanmapper.core.model.PermissionTier
import com.titanmapper.core.data.settings.SettingsRepository
import com.titanmapper.service.api.CapabilityMonitor
import com.titanmapper.service.api.LatencyStats
import com.titanmapper.service.api.LatencyMonitor
import com.titanmapper.service.api.PerformanceMonitor
import com.titanmapper.service.api.PerformanceSnapshot
import com.titanmapper.service.api.ServiceController
import com.titanmapper.service.api.ServiceState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DashboardUiState(
    val service: ServiceState = ServiceState(),
    val capabilities: CapabilitySnapshot = CapabilitySnapshot(),
    val latency: LatencyStats = LatencyStats(),
    val performance: PerformanceSnapshot = PerformanceSnapshot()
)

sealed interface DashboardUiEvent {
    data object ToggleService : DashboardUiEvent
    data class SelectTier(val tier: PermissionTier) : DashboardUiEvent
    data object RefreshCapabilities : DashboardUiEvent
}

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val controller: ServiceController,
    private val capabilityMonitor: CapabilityMonitor,
    private val latencyMonitor: LatencyMonitor,
    private val performanceMonitor: PerformanceMonitor,
    private val settings: SettingsRepository
) : ViewModel() {

    val uiState: StateFlow<DashboardUiState> = combine(
        controller.state, capabilityMonitor.snapshot, latencyMonitor.stats, performanceMonitor.snapshot
    ) { service, caps, latency, perf -> DashboardUiState(service, caps, latency, perf) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    init {
        capabilityMonitor.refresh()
    }

    fun onEvent(event: DashboardUiEvent) {
        when (event) {
            DashboardUiEvent.ToggleService -> if (uiState.value.service.running) controller.stop() else controller.start()
            DashboardUiEvent.RefreshCapabilities -> capabilityMonitor.refresh()
            is DashboardUiEvent.SelectTier -> viewModelScope.launch {
                settings.update { it.copy(preferredTier = event.tier) }
                if (uiState.value.service.running) {
                    controller.stop()
                    controller.start()
                }
            }
        }
    }
}
