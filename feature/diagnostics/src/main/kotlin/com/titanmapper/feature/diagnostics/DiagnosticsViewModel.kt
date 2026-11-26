package com.titanmapper.feature.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.titanmapper.core.data.repository.EventLogRepository
import com.titanmapper.core.data.settings.SettingsRepository
import com.titanmapper.core.model.EventLogEntry
import com.titanmapper.core.model.LogLevel
import com.titanmapper.core.model.RawInputEvent
import com.titanmapper.service.api.InputEventStream
import com.titanmapper.service.api.LatencyMonitor
import com.titanmapper.service.api.LatencyStats
import com.titanmapper.service.api.PerformanceMonitor
import com.titanmapper.service.api.PerformanceSnapshot
import com.titanmapper.service.api.ServiceController
import com.titanmapper.service.pipeline.LatencyMonitorImpl
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DiagnosticsUiState(
    val recentEvents: List<RawInputEvent> = emptyList(),
    val paused: Boolean = false,
    val latency: LatencyStats = LatencyStats(),
    val latencyMonitorEnabled: Boolean = false,
    val performance: PerformanceSnapshot = PerformanceSnapshot(),
    val logFilter: LogLevel? = null
)

sealed interface DiagnosticsUiEvent {
    data object TogglePause : DiagnosticsUiEvent
    data class ToggleLatencyMonitor(val enabled: Boolean) : DiagnosticsUiEvent
    data class SetLogFilter(val level: LogLevel?) : DiagnosticsUiEvent
    data object ClearLogs : DiagnosticsUiEvent
}

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val inputEventStream: InputEventStream,
    private val latencyMonitor: LatencyMonitor,
    private val performanceMonitor: PerformanceMonitor,
    private val eventLog: EventLogRepository,
    private val settings: SettingsRepository,
    private val controller: ServiceController
) : ViewModel() {

    private val recent = MutableStateFlow<List<RawInputEvent>>(emptyList())
    private val paused = MutableStateFlow(false)
    private val logFilter = MutableStateFlow<LogLevel?>(null)

    /** Stage 1: pair up live-input state (changes often) separately from latency/perf (changes ~1/s). */
    private val liveInput = combine(recent, paused) { events, isPaused -> events to isPaused }
    private val telemetry = combine(latencyMonitor.stats, performanceMonitor.snapshot) { lat, perf -> lat to perf }
    private val filterAndSettings = combine(logFilter, settings.settings) { filter, s -> filter to s.latencyMonitorEnabled }

    val uiState: StateFlow<DiagnosticsUiState> = combine(liveInput, telemetry, filterAndSettings) { input, tel, filterSettings ->
        DiagnosticsUiState(
            recentEvents = input.first,
            paused = input.second,
            latency = tel.first,
            performance = tel.second,
            latencyMonitorEnabled = filterSettings.second,
            logFilter = filterSettings.first
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiagnosticsUiState())

    val logs: StateFlow<List<EventLogEntry>> = combine(eventLog.observeRecent(500), logFilter) { entries, filter ->
        if (filter == null) entries else entries.filter { it.level == filter }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            inputEventStream.events.collect { event ->
                if (paused.value) return@collect
                recent.value = (listOf(event) + recent.value).take(MAX_EVENTS)
            }
        }
    }

    fun onEvent(event: DiagnosticsUiEvent) {
        when (event) {
            DiagnosticsUiEvent.TogglePause -> paused.value = !paused.value
            is DiagnosticsUiEvent.ToggleLatencyMonitor -> viewModelScope.launch {
                settings.update { it.copy(latencyMonitorEnabled = event.enabled) }
                (latencyMonitor as? LatencyMonitorImpl)?.setEnabled(event.enabled)
            }
            is DiagnosticsUiEvent.SetLogFilter -> logFilter.value = event.level
            DiagnosticsUiEvent.ClearLogs -> viewModelScope.launch { eventLog.clear() }
        }
    }

    suspend fun exportText(): String = eventLog.exportText()

    /**
     * Whether a key/button event's key or mouse button has a currently-enabled binding -- note
     * this reflects the CURRENT profile/binding state, not necessarily the state at the moment
     * the event was captured (the binding could have changed since, e.g. a profile switch). It
     * is deliberately not claimed to be "whether this exact past event was dispatched" -- that
     * would require correlating against MappingEngine's dispatch-result callback, which this
     * screen does not yet do; this is an honest, currently-true "would this be captured right
     * now" check, not a historical record. Null for event kinds a binding can't apply to
     * (pointer motion, scroll, device connect/disconnect).
     */
    fun isBound(event: RawInputEvent): Boolean? = when (event) {
        is RawInputEvent.Key -> controller.isKeyBound(event.keyCode)
        is RawInputEvent.Button -> controller.isButtonBound(event.button)
        else -> null
    }

    private companion object {
        const val MAX_EVENTS = 200
    }
}
