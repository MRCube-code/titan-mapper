package com.titanmapper.service.pipeline

import com.titanmapper.core.common.ApplicationScope
import com.titanmapper.core.data.repository.EventLogRepository
import com.titanmapper.core.data.repository.ProfileRepository
import com.titanmapper.core.data.settings.SettingsRepository
import com.titanmapper.core.model.CaptureTier
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single place that wires capture -> [InputEventBus] -> [MappingEngine] -> dispatch sink,
 * as described in Architecture §4.6 and §8. Owns tier lifecycle, profile resolution/switching,
 * the pointer pipeline, idle teardown, and automatic (never automatic-upgrade) tier downgrade.
 *
 * Concurrency: [MappingEngine] and [activeBackend] are mutable, non-thread-safe state. Every
 * mutation of them happens on exactly one coroutine — the "actor" launched by [start] inside
 * [runActor] — which is the single consumer of a [merge]d stream of input events, profile
 * updates, health-check ticks and idle-teardown ticks. Nothing outside that actor coroutine
 * (health-check/idle-tick producers, [start], [stop]) ever touches [engine] or [activeBackend]
 * directly; they only ever push a tick/event into a flow the actor reads. [start] and [stop]
 * themselves are additionally serialized by [lifecycleMutex] so they can never interleave.
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

    /** Guards start()/stop() so they can never run concurrently with each other. */
    private val lifecycleMutex = Mutex()

    private var runLoopJob: Job? = null
    private var healthCheckJob: Job? = null
    private var idleCheckJob: Job? = null

    // --- actor-confined state: mutated ONLY from inside runActor()'s collect lambda, or from
    // start()/stop() while lifecycleMutex is held and the actor is not yet running / has been
    // joined. Never read or written from healthCheckJob/idleCheckJob directly. ---
    private var activeBackend: TierBackend? = null
    private var engine: MappingEngine? = null
    private val pointerTransform = PointerTransform(com.titanmapper.core.model.SensitivityPreset.DEFAULT)
    private val pointerDelta = FloatArray(2)
    private var lastEventAtNanos = System.nanoTime()
    private var currentProfile: MappingProfile? = null

    /** Pure signal flows: producers never touch pipeline state, they only tick the actor. */
    private val healthTicks = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val idleTicks = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private sealed interface PipelineCommand {
        data class Event(val event: RawInputEvent) : PipelineCommand
        data class ApplyProfile(val profile: MappingProfile?) : PipelineCommand
        data object HealthCheck : PipelineCommand
        data object IdleCheck : PipelineCommand
    }

    override fun start() {
        scope.launch {
            lifecycleMutex.withLock {
                if (runLoopJob?.isActive == true) return@withLock // already running; idempotent

                val requestedTier = settings.settings.first().preferredTier
                _state.value = _state.value.copy(running = true, requestedTier = requestedTier, message = null)
                performanceMonitor.start(scope)

                val started = switchTierInternal(requestedTier)
                if (!started) {
                    performanceMonitor.stop()
                    _state.value = _state.value.copy(
                        running = false,
                        message = "Couldn't start ${requestedTier.name.lowercase()} mode. Check its permission and try again."
                    )
                    return@withLock
                }

                runLoopJob = scope.launch {
                    try {
                        runActor()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // The actor crashed outside of a requested stop(): tear ourselves down so
                        // the UI doesn't keep showing "running" with a dead pipeline, and so a
                        // later start() isn't blocked by a stale non-null runLoopJob.
                        eventLog.log(LogLevel.ERROR, "Pipeline", "Stopped unexpectedly: ${e.message}")
                        engine?.releaseAll()
                        activeBackend?.stop()
                        engine = null
                        activeBackend = null
                        performanceMonitor.stop()
                        _state.value = _state.value.copy(running = false, message = "Stopped unexpectedly: ${e.message}")
                    }
                }
                healthCheckJob = scope.launch { healthCheckLoop() }
                idleCheckJob = scope.launch { idleTickLoop() }
            }
        }
    }

    override fun stop() {
        scope.launch {
            lifecycleMutex.withLock {
                val actor = runLoopJob
                runLoopJob = null
                healthCheckJob?.cancel(); healthCheckJob = null
                idleCheckJob?.cancel(); idleCheckJob = null
                // Wait for the actor to actually finish before touching engine/backend below, so
                // a release-all can never race the last in-flight event it was processing.
                actor?.cancelAndJoin()
                engine?.releaseAll()
                activeBackend?.stop()
                engine = null
                activeBackend = null
                performanceMonitor.stop()
                _state.value = _state.value.copy(running = false, activeProfileName = null)
                eventLog.log(LogLevel.INFO, "Pipeline", "Stopped")
            }
        }
    }

    /**
     * The single consumer of every source that can touch pipeline state. Cancelling this job
     * (from [stop]) is what cancels the upstream flows it [merge]s — including the profile
     * resolution pipeline, which has no job of its own and therefore cannot leak or duplicate
     * across a stop()/start() cycle.
     */
    private suspend fun runActor() {
        merge(
            bus.events.map<RawInputEvent, PipelineCommand> { PipelineCommand.Event(it) },
            profileUpdates().map<MappingProfile?, PipelineCommand> { PipelineCommand.ApplyProfile(it) },
            healthTicks.map<Unit, PipelineCommand> { PipelineCommand.HealthCheck },
            idleTicks.map<Unit, PipelineCommand> { PipelineCommand.IdleCheck }
        ).collect { command ->
            when (command) {
                is PipelineCommand.Event -> handleEvent(command.event)
                is PipelineCommand.ApplyProfile -> applyProfile(command.profile)
                PipelineCommand.HealthCheck -> handleHealthCheck()
                PipelineCommand.IdleCheck -> handleIdleCheck()
            }
        }
    }

    private fun profileUpdates(): Flow<MappingProfile?> =
        combine(
            settings.settings.distinctUntilChanged { a, b -> a.autoProfileSwitch == b.autoProfileSwitch },
            foregroundAppTracker.foregroundPackage
        ) { s, pkg -> s.autoProfileSwitch to pkg }
            .combine(profiles.observeProfiles()) { (autoSwitch, pkg), all -> Triple(autoSwitch, pkg, all) }
            .map { (autoSwitch, pkg, all) ->
                if (autoSwitch) com.titanmapper.core.model.ProfileResolver.resolve(all, null, pkg)
                else all.firstOrNull { it.isActive }
            }
            .distinctUntilChanged { a, b -> a?.id == b?.id }

    private fun handleEvent(event: RawInputEvent) {
        lastEventAtNanos = System.nanoTime()
        val backend = activeBackend ?: return
        val currentEngine = engine ?: return
        if (event is RawInputEvent.Motion) {
            pointerTransform.transform(event.deltaX, event.deltaY, pointerDelta)
            // Pointer deltas bypass binding resolution entirely -> straight to the sink as
            // a MouseInjection.Move, since there's no discrete "trigger" for motion.
            backend.sink.dispatch(
                com.titanmapper.core.model.MappedAction.MouseInjection.Move(pointerDelta[0], pointerDelta[1]),
                com.titanmapper.service.engine.DispatchPhase.ONCE
            )
        } else {
            currentEngine.handle(event)
        }
    }

    private fun applyProfile(profile: MappingProfile?) {
        // Release under the OLD profile's bindings before the index swaps, so nothing held under
        // a binding that no longer exists (or no longer means the same thing) is left stuck.
        engine?.releaseAll()
        engine?.setProfile(profile)
        pointerTransform.updatePreset(profile?.sensitivity ?: com.titanmapper.core.model.SensitivityPreset.DEFAULT)
        currentProfile = profile
        _state.value = _state.value.copy(activeProfileName = profile?.name)
        eventLog.log(LogLevel.INFO, "Profile", "Active profile: ${profile?.name ?: "none"}")
    }

    private suspend fun handleHealthCheck() {
        val backend = activeBackend ?: return
        if (backend.isHealthy()) return
        val requested = _state.value.requestedTier
        val lower = PermissionTier.entries.filter { it.level < requested.level }.maxByOrNull { it.level }
            ?: PermissionTier.STANDARD
        eventLog.log(LogLevel.WARN, "Pipeline", "${requested.name} tier became unavailable; falling back to ${lower.name}")
        val ok = switchTierInternal(lower)
        // Note: requestedTier is intentionally left untouched here — it stays the tier the user
        // actually asked for, so the "Downgraded from X" chip and a later manual restart both
        // keep working. Only activeTier reflects the automatic fallback.
        _state.value = _state.value.copy(
            activeTier = if (ok) lower else _state.value.activeTier,
            message = "Switched down to ${lower.name.lowercase()} mode because the previous tier stopped working."
        )
    }

    private fun handleIdleCheck() {
        val backend = activeBackend ?: return
        if (backend.tier == CaptureTier.STANDARD) return // nothing expensive to release
        val minutes = cachedIdleTeardownMinutes
        val idleNanos = System.nanoTime() - lastEventAtNanos
        if (idleNanos > minutes * 60_000_000_000L) {
            // Release first: a key/button still logically "held" when we tear down the backend
            // that was going to send its UP event would otherwise be stuck forever.
            engine?.releaseAll()
            backend.stop()
            activeBackend = null
            engine = null
            eventLog.log(LogLevel.INFO, "Pipeline", "Idle teardown: released ${backend.tier.name} session after ${minutes}m of inactivity")
        }
    }

    /** Actor-confined: swaps the active backend/engine. @return true if the tier started. */
    private suspend fun switchTierInternal(tier: PermissionTier): Boolean {
        engine?.releaseAll()
        activeBackend?.stop()
        activeBackend = null
        engine = null

        val backend = backendFor(tier)
        val started = backend.start()
        if (!started) return false // fields stay null: no stale backend/engine left behind

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
        _state.value = _state.value.copy(activeTier = tier, message = null)
        eventLog.log(LogLevel.INFO, "Pipeline", "Started on ${tier.name} tier")
        return true
    }

    private fun backendFor(tier: PermissionTier): TierBackend = when (tier) {
        PermissionTier.STANDARD -> standardTier
        PermissionTier.ACCESSIBILITY -> accessibilityTier
        PermissionTier.SHIZUKU -> shizukuTier
        PermissionTier.ROOT -> rootTier
    }

    /** Mirrors the current idleTeardownMinutes setting so [handleIdleCheck] (actor-confined, and
     *  therefore not suspend) doesn't need to re-read DataStore; refreshed by [idleTickLoop]. */
    @Volatile private var cachedIdleTeardownMinutes: Int = 10

    private suspend fun healthCheckLoop() {
        while (true) {
            delay(HEALTH_CHECK_INTERVAL_MS)
            healthTicks.emit(Unit)
        }
    }

    /** Tears down expensive idle tiers (Shizuku binder, root shell) after sustained inactivity (Architecture §11). */
    private suspend fun idleTickLoop() {
        while (true) {
            val minutes = settings.settings.first().idleTeardownMinutes
            cachedIdleTeardownMinutes = minutes
            delay(minutes * 60_000L)
            idleTicks.emit(Unit)
        }
    }

    private companion object {
        const val HEALTH_CHECK_INTERVAL_MS = 5_000L
    }
}
