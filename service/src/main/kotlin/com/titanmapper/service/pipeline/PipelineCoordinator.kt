package com.titanmapper.service.pipeline

import com.titanmapper.core.common.ApplicationScope
import com.titanmapper.core.data.repository.EventLogRepository
import com.titanmapper.core.data.repository.ProfileRepository
import com.titanmapper.core.data.settings.SettingsRepository
import com.titanmapper.core.model.CaptureTier
import com.titanmapper.core.model.KeyAction
import com.titanmapper.core.model.LogLevel
import com.titanmapper.core.model.MappingProfile
import com.titanmapper.core.model.PermissionTier
import com.titanmapper.core.model.RawInputEvent
import com.titanmapper.service.api.ServiceController
import com.titanmapper.service.api.ServiceState
import com.titanmapper.service.engine.MappingEngine
import com.titanmapper.service.engine.PointerTransform
import com.titanmapper.service.input.InputEventBus
import com.titanmapper.service.tier.AccessibilityTier
import com.titanmapper.service.tier.RootTier
import com.titanmapper.service.tier.ShizukuTier
import com.titanmapper.service.tier.StandardTier
import com.titanmapper.service.tier.TierBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single place that wires capture -> [InputEventBus] -> [MappingEngine] -> dispatch sink,
 * as described in Architecture §4.6 and §8. Owns tier lifecycle, profile resolution/switching,
 * the pointer pipeline, idle teardown, and automatic (never automatic-upgrade) tier downgrade.
 */
@Singleton
class PipelineCoordinator @Inject constructor(
    private val bus: InputEventBus,
    private val profiles: ProfileRepository,
    private val settings: SettingsRepository,
    private val eventLog: EventLogRepository,
    private val foregroundAppTracker: ForegroundAppTracker,
    private val latencyMonitor: LatencyMonitorImpl,
    private val performanceMonitor: PerformanceMonitorImpl,
    private val standardTier: StandardTier,
    private val accessibilityTier: AccessibilityTier,
    private val shizukuTier: ShizukuTier,
    private val rootTier: RootTier,
    @ApplicationScope private val scope: CoroutineScope
) : ServiceController {

    private val _state = MutableStateFlow(ServiceState())
    override val state: StateFlow<ServiceState> get() = _state

    private var runLoopJob: Job? = null
    private var consumerJob: Job? = null
    private var healthCheckJob: Job? = null
    private var idleCheckJob: Job? = null

    private var activeBackend: TierBackend? = null
    private var engine: MappingEngine? = null
    private val pointerTransform = PointerTransform(com.titanmapper.core.model.SensitivityPreset.DEFAULT)
    private val pointerDelta = FloatArray(2)

    private var lastEventAtNanos = System.nanoTime()
    private var currentProfile: MappingProfile? = null

    override fun start() {
        if (runLoopJob != null) return
        _state.value = _state.value.copy(running = true, message = null)
        performanceMonitor.start(scope)
        runLoopJob = scope.launch {
            val requestedTier = settings.settings.first().preferredTier
            if (!switchTier(requestedTier)) {
                _state.value = _state.value.copy(
                    running = false,
                    message = "Couldn't start ${requestedTier.name.lowercase()} mode. Check its permission and try again."
                )
                return@launch
            }
            launchProfileResolution()
            launchHealthCheck()
            launchIdleTeardown()
        }
    }

    override fun stop() {
        runLoopJob?.cancel(); runLoopJob = null
        consumerJob?.cancel(); consumerJob = null
        healthCheckJob?.cancel(); healthCheckJob = null
        idleCheckJob?.cancel(); idleCheckJob = null
        engine?.releaseAll()
        activeBackend?.stop()
        activeBackend = null
        performanceMonitor.stop()
        _state.value = _state.value.copy(running = false, activeProfileName = null)
        eventLog.log(LogLevel.INFO, "Pipeline", "Stopped")
    }

    private fun launchProfileResolution() {
        scope.launch {
            combine(
                settings.settings.distinctUntilChanged { a, b -> a.autoProfileSwitch == b.autoProfileSwitch },
                foregroundAppTracker.foregroundPackage
            ) { s, pkg -> s.autoProfileSwitch to pkg }
                .combine(profiles.observeProfiles()) { (autoSwitch, pkg), all -> Triple(autoSwitch, pkg, all) }
                .collect { (autoSwitch, pkg, all) ->
                    val resolved = if (autoSwitch) {
                        com.titanmapper.core.model.ProfileResolver.resolve(all, null, pkg)
                    } else {
                        all.firstOrNull { it.isActive }
                    }
                    if (resolved?.id != currentProfile?.id) applyProfile(resolved)
                }
        }
    }

    private fun applyProfile(profile: MappingProfile?) {
        engine?.releaseAll()
        engine?.setProfile(profile)
        pointerTransform.updatePreset(profile?.sensitivity ?: com.titanmapper.core.model.SensitivityPreset.DEFAULT)
        currentProfile = profile
        _state.value = _state.value.copy(activeProfileName = profile?.name)
        eventLog.log(LogLevel.INFO, "Profile", "Active profile: ${profile?.name ?: "none"}")
    }

    /** @return true if the tier started successfully. */
    private suspend fun switchTier(tier: PermissionTier): Boolean {
        consumerJob?.cancel()
        engine?.releaseAll()
        activeBackend?.stop()

        val backend = backendFor(tier)
        val started = backend.start()
        if (!started) return false

        activeBackend = backend
        val newEngine = MappingEngine(backend.sink, scope) { resolved ->
            latencyMonitor.record(resolved.latencyNanos)
            if (!resolved.accepted) {
                eventLog.log(
                    LogLevel.WARN, "Dispatch",
                    "${tier.name} tier couldn't perform: ${resolved.binding.action::class.simpleName}"
                )
            }
        }
        newEngine.setProfile(currentProfile)
        engine = newEngine

        consumerJob = scope.launch {
            bus.events.collect { event ->
                lastEventAtNanos = System.nanoTime()
                if (event is RawInputEvent.Motion) {
                    pointerTransform.transform(event.deltaX, event.deltaY, pointerDelta)
                    // Pointer deltas bypass binding resolution entirely -> straight to the sink as
                    // a MouseInjection.Move, since there's no discrete "trigger" for motion.
                    backend.sink.dispatch(
                        com.titanmapper.core.model.MappedAction.MouseInjection.Move(pointerDelta[0], pointerDelta[1]),
                        com.titanmapper.service.engine.DispatchPhase.ONCE
                    )
                } else {
                    newEngine.handle(event)
                }
            }
        }
        _state.value = _state.value.copy(requestedTier = tier, activeTier = tier, message = null)
        eventLog.log(LogLevel.INFO, "Pipeline", "Started on ${tier.name} tier")
        return true
    }

    private fun backendFor(tier: PermissionTier): TierBackend = when (tier) {
        PermissionTier.STANDARD -> standardTier
        PermissionTier.ACCESSIBILITY -> accessibilityTier
        PermissionTier.SHIZUKU -> shizukuTier
        PermissionTier.ROOT -> rootTier
    }

    private fun launchHealthCheck() {
        healthCheckJob = scope.launch {
            while (isActive) {
                delay(HEALTH_CHECK_INTERVAL_MS)
                val backend = activeBackend ?: continue
                if (!backend.isHealthy()) {
                    val requested = _state.value.requestedTier
                    val lower = PermissionTier.entries.filter { it.level < requested.level }.maxByOrNull { it.level }
                        ?: PermissionTier.STANDARD
                    eventLog.log(LogLevel.WARN, "Pipeline", "${requested.name} tier became unavailable; falling back to ${lower.name}")
                    val ok = switchTier(lower)
                    _state.value = _state.value.copy(
                        activeTier = if (ok) lower else _state.value.activeTier,
                        message = "Switched down to ${lower.name.lowercase()} mode because the previous tier stopped working."
                    )
                }
            }
        }
    }

    /** Tears down expensive idle tiers (Shizuku binder, root shell) after sustained inactivity (Architecture §11). */
    private fun launchIdleTeardown() {
        idleCheckJob = scope.launch {
            while (isActive) {
                val minutes = settings.settings.first().idleTeardownMinutes
                delay(minutes * 60_000L)
                val idleNanos = System.nanoTime() - lastEventAtNanos
                val backend = activeBackend
                if (backend != null && backend.tier != CaptureTier.STANDARD && idleNanos > minutes * 60_000_000_000L) {
                    backend.stop()
                    eventLog.log(LogLevel.INFO, "Pipeline", "Idle teardown: released ${backend.tier.name} session after ${minutes}m of inactivity")
                }
            }
        }
    }

    private companion object {
        const val HEALTH_CHECK_INTERVAL_MS = 5_000L
    }
}
