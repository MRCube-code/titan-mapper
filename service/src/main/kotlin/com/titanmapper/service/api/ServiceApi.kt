package com.titanmapper.service.api

import android.view.KeyEvent
import android.view.MotionEvent
import com.titanmapper.core.model.CapabilitySnapshot
import com.titanmapper.core.model.MacroDefinition
import com.titanmapper.core.model.MacroStep
import com.titanmapper.core.model.PermissionTier
import com.titanmapper.core.model.RawInputEvent
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/*
 * Public surface of :service consumed by :feature:* and :app. Feature modules depend ONLY on
 * these interfaces; the implementations are Hilt-bound inside :service. Keeping this contract
 * small and stable is what lets the capture/dispatch internals change without touching UI.
 */

/** Runtime state of the remapping pipeline. */
data class ServiceState(
    val running: Boolean = false,
    /** Tier the user asked for. */
    val requestedTier: PermissionTier = PermissionTier.STANDARD,
    /** Tier actually in use; lower than requested after an automatic downgrade. */
    val activeTier: PermissionTier = PermissionTier.STANDARD,
    val activeProfileName: String? = null,
    val foregroundPackage: String? = null,
    /** Human-readable reason when running degraded or failed to start; null when healthy. */
    val message: String? = null
)

interface ServiceController {
    val state: StateFlow<ServiceState>

    /** Starts the foreground service with the tier from settings. Safe to call repeatedly. */
    fun start()
    fun stop()
}

/** Which tiers are usable right now (root granted, Shizuku bound, accessibility enabled). */
interface CapabilityMonitor {
    val snapshot: StateFlow<CapabilitySnapshot>

    /** Re-probe now (e.g. when the app returns to the foreground). */
    fun refresh()
}

/** Hot stream of normalized input events for diagnostics, the macro recorder and overlay preview. */
interface InputEventStream {
    val events: SharedFlow<RawInputEvent>
}

data class LatencyStats(
    val sampleCount: Long = 0,
    val lastMicros: Long = 0,
    val averageMicros: Long = 0,
    val p95Micros: Long = 0,
    val maxMicros: Long = 0
)

/** Capture-to-dispatch latency, measured only while enabled in settings (zero cost otherwise). */
interface LatencyMonitor {
    val stats: StateFlow<LatencyStats>
    fun reset()
}

data class PerformanceSnapshot(
    val eventsPerSecond: Int = 0,
    val droppedEvents: Long = 0,
    val heapUsedMb: Int = 0,
    val uptimeMillis: Long = 0
)

interface PerformanceMonitor {
    val snapshot: StateFlow<PerformanceSnapshot>
}

data class RecorderState(val recording: Boolean = false, val capturedEvents: Int = 0)

interface MacroRecorder {
    val state: StateFlow<RecorderState>
    fun start()

    /** Stops and returns the recorded steps with human jitter collapsed into clean delays. */
    suspend fun stop(): List<MacroStep>
}

interface MacroRunner {
    /** Ids of macros currently executing. */
    val running: StateFlow<Set<String>>
    fun run(macro: MacroDefinition)
    fun cancel(macroId: String)
    fun cancelAll()
}

/**
 * Standard-tier capture: an Activity forwards events it receives while focused. Returns true if
 * the event was consumed by a binding. Used by MainActivity.dispatchKeyEvent / dispatchGenericMotionEvent.
 */
interface ActivityInputBridge {
    fun onKeyEvent(event: KeyEvent): Boolean
    fun onGenericMotionEvent(event: MotionEvent): Boolean
}

/** A discovered, installed plugin that offers extra actions. */
data class PluginInfo(
    val packageName: String,
    val label: String,
    val enabled: Boolean,
    val actions: List<PluginActionInfo>
)

data class PluginActionInfo(val id: String, val label: String)

interface PluginRegistry {
    val plugins: StateFlow<List<PluginInfo>>
    fun refresh()
    fun setEnabled(packageName: String, enabled: Boolean)
}
