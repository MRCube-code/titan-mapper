package com.titanmapper.feature.diagnostics

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.titanmapper.core.model.CaptureTier
import com.titanmapper.core.model.KeyAction
import com.titanmapper.core.model.LogLevel
import com.titanmapper.core.model.RawInputEvent
import com.titanmapper.core.ui.components.TitanCard
import com.titanmapper.core.ui.format.displayName
import com.titanmapper.core.ui.format.formatMicros
import com.titanmapper.core.ui.theme.TitanSpacing
import kotlinx.coroutines.launch

@Composable
fun DiagnosticsRoute(viewModel: DiagnosticsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Live events") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Latency & performance") })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Event log") })
        }
        when (tab) {
            0 -> LiveEventsTab(state, viewModel::onEvent, viewModel::isBound)
            1 -> PerformanceTab(state, viewModel::onEvent)
            else -> EventLogTab(logs, state.logFilter, viewModel::onEvent) {
                scope.launch {
                    val text = viewModel.exportText()
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    context.startActivity(Intent.createChooser(intent, "Share event log"))
                }
            }
        }
    }
}

@Composable
private fun LiveEventsTab(state: DiagnosticsUiState, onEvent: (DiagnosticsUiEvent) -> Unit, isBound: (RawInputEvent) -> Boolean?) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(TitanSpacing.m),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Real counters only -- Captured and Dropped both come straight from the actual
            // capture bus (InputEventBus.droppedCount via PerformanceSnapshot), never fabricated.
            // "Mapped now" counts, among the events currently in this list, how many reference a
            // key/mouse button that is bound right now (see DiagnosticsViewModel.isBound's KDoc
            // on why this is a live check, not a historical record of what happened at capture).
            val mappedNow = state.recentEvents.count { isBound(it) == true }
            Column {
                Text("${state.recentEvents.size} captured · $mappedNow mapped now · ${state.performance.droppedEvents} dropped", style = MaterialTheme.typography.bodyMedium)
            }
            IconButton(onClick = { onEvent(DiagnosticsUiEvent.TogglePause) }) {
                Icon(if (state.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, contentDescription = if (state.paused) "Resume" else "Pause")
            }
        }
        LazyColumn(contentPadding = PaddingValues(horizontal = TitanSpacing.m)) {
            items(state.recentEvents) { event -> EventRow(event, isBound(event)) }
        }
    }
}

@Composable
private fun EventRow(event: RawInputEvent, bound: Boolean?) {
    val (label, color) = when (event) {
        is RawInputEvent.Key -> "${event.action.label()} key ${event.keyCode}" to MaterialTheme.colorScheme.primary
        is RawInputEvent.Button -> "${event.action.label()} ${event.button}" to MaterialTheme.colorScheme.secondary
        is RawInputEvent.Motion -> "Move Δ${event.deltaX.toInt()},${event.deltaY.toInt()}" to MaterialTheme.colorScheme.tertiary
        is RawInputEvent.Scroll -> "Scroll ${event.direction}" to MaterialTheme.colorScheme.tertiary
        is RawInputEvent.DeviceConnectionChanged -> "${if (event.connected) "Connected" else "Disconnected"}: ${event.deviceFingerprint.deviceName}" to MaterialTheme.colorScheme.error
    }
    val fp = event.deviceFingerprint
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = TitanSpacing.xs)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = color)
            Row(horizontalArrangement = Arrangement.spacedBy(TitanSpacing.s)) {
                if (bound != null) {
                    Text(
                        if (bound) "Bound" else "Unbound",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (bound) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(event.sourceTier.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            "${fp.deviceName} · vendor ${fp.vendorId.toString(16)} product ${fp.productId.toString(16)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun KeyAction.label() = if (this == KeyAction.DOWN) "↓" else "↑"

@Composable
private fun PerformanceTab(state: DiagnosticsUiState, onEvent: (DiagnosticsUiEvent) -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(TitanSpacing.l), verticalArrangement = Arrangement.spacedBy(TitanSpacing.m)) {
        TitanCard {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Latency monitor", style = MaterialTheme.typography.titleMedium)
                Switch(checked = state.latencyMonitorEnabled, onCheckedChange = { onEvent(DiagnosticsUiEvent.ToggleLatencyMonitor(it)) })
            }
            if (state.latencyMonitorEnabled) {
                Text("Average: ${formatMicros(state.latency.averageMicros)}")
                Text("p95: ${formatMicros(state.latency.p95Micros)}")
                Text("Max: ${formatMicros(state.latency.maxMicros)}")
                Text("Samples: ${state.latency.sampleCount}")
            } else {
                Text("Disabled to avoid overhead. Turn on to measure capture-to-dispatch latency.", style = MaterialTheme.typography.bodySmall)
            }
        }
        TitanCard {
            Text("Performance", style = MaterialTheme.typography.titleMedium)
            Text("Events/sec: ${state.performance.eventsPerSecond}")
            Text("Dropped: ${state.performance.droppedEvents}")
            Text("Heap used: ${state.performance.heapUsedMb} MB")
        }
    }
}

@Composable
private fun EventLogTab(logs: List<com.titanmapper.core.model.EventLogEntry>, filter: LogLevel?, onEvent: (DiagnosticsUiEvent) -> Unit, onShare: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(TitanSpacing.m), horizontalArrangement = Arrangement.spacedBy(TitanSpacing.s)) {
            FilterChip(selected = filter == null, onClick = { onEvent(DiagnosticsUiEvent.SetLogFilter(null)) }, label = { Text("All") })
            LogLevel.entries.forEach { level ->
                FilterChip(selected = filter == level, onClick = { onEvent(DiagnosticsUiEvent.SetLogFilter(level)) }, label = { Text(level.name) })
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = TitanSpacing.m), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { onEvent(DiagnosticsUiEvent.ClearLogs) }) { Text("Clear") }
            IconButton(onClick = onShare) { Icon(Icons.Filled.Share, contentDescription = "Share log") }
        }
        LazyColumn(contentPadding = PaddingValues(TitanSpacing.m)) {
            items(logs) { entry ->
                Text(
                    "${entry.level} · ${entry.category}: ${entry.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = when (entry.level) {
                        LogLevel.ERROR -> MaterialTheme.colorScheme.error
                        LogLevel.WARN -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                )
            }
        }
    }
}
