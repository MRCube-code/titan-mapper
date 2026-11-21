package com.titanmapper.feature.dashboard

import android.content.Context
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
import com.titanmapper.service.services.TitanInputService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
    @ApplicationContext private val context: Context,
    // Only read for display (uiState below). Never call start()/stop() on this directly from the
    // UI layer -- see onEvent's ToggleService/SelectTier handling for why.
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
            // The pipeline (ServiceController / PipelineCoordinator) must only ever be started or
            // stopped BY TitanInputService itself, from its onStartCommand/onDestroy -- never
            // directly from the UI. Routing through the service is what actually posts the
            // foreground notification and gives the process the foreground-service lifetime
            // guarantee; calling controller.start() here would run the pipeline with no
            // foreground service behind it, which Android can and will kill as soon as the app
            // is backgrounded.
            DashboardUiEvent.ToggleService -> if (uiState.value.service.running) {
                TitanInputService.stop(context)
            } else {
                TitanInputService.start(context)
            }
            DashboardUiEvent.RefreshCapabilities -> capabilityMonitor.refresh()
            is DashboardUiEvent.SelectTier -> viewModelScope.launch {
                settings.update { it.copy(preferredTier = event.tier) }
                if (uiState.value.service.running) {
                    // A running pipeline doesn't pick up a new preferredTier by itself; cycle the
                    // whole foreground service so it restarts on the newly selected tier.
                    TitanInputService.stop(context)
                    TitanInputService.start(context)
                }
            }
        }
    }
}
