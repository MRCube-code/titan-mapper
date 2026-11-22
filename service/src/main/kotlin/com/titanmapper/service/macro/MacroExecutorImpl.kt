package com.titanmapper.service.macro

import com.titanmapper.core.common.ApplicationScope
import com.titanmapper.core.data.repository.EventLogRepository
import com.titanmapper.core.model.KeyAction
import com.titanmapper.core.model.LogLevel
import com.titanmapper.core.model.MacroCondition
import com.titanmapper.core.model.MacroDefinition
import com.titanmapper.core.model.MacroStep
import com.titanmapper.core.model.MappedAction
import com.titanmapper.service.api.MacroRunner
import com.titanmapper.service.engine.DispatchPhase
import com.titanmapper.service.engine.OutputDispatchSink
import com.titanmapper.service.pipeline.ForegroundAppTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs [MacroDefinition]s (Architecture §10). Each running macro gets its own coroutine, capped
 * at [MAX_CONCURRENT] simultaneously so one runaway macro can't starve input processing or spawn
 * unbounded work. Steps are dispatched through whichever [OutputDispatchSink] is currently
 * active — this class is tier-agnostic by design.
 *
 * Toggle state for [MacroCondition.ToggleIsActive] is read from a snapshot the
 * [com.titanmapper.service.pipeline.PipelineCoordinator] publishes; this class never touches
 * MappingEngine internals directly, keeping the two testable in isolation.
 *
 * [run] can be called from the UI thread (a tap on "run macro") while [jobs] entries are removed
 * from whatever thread [scope] (Dispatchers.Default, multiple worker threads) happens to run a
 * finishing macro's `finally` block on, so [jobs] has to be safe for concurrent, unsynchronized
 * access from both sides -- a plain `HashMap` is not. The "don't stack on yourself" check is also
 * made atomic (via [ConcurrentHashMap.putIfAbsent] against a not-yet-started, [CoroutineStart.LAZY]
 * job) so two near-simultaneous [run] calls for the same macro id can't both win the check and end
 * up with two coroutines executing the same macro while only one is tracked/cancelable.
 */
@Singleton
class MacroExecutorImpl @Inject constructor(
    private val foregroundAppTracker: ForegroundAppTracker,
    private val eventLog: EventLogRepository,
    @ApplicationScope private val scope: CoroutineScope
) : MacroRunner {

    /** Supplied by the pipeline: which toggle bindings are currently "on" and which devices are connected. */
    var toggleStateProvider: (String) -> Boolean = { false }
    var deviceConnectedProvider: (String) -> Boolean = { false }
    var activeSink: OutputDispatchSink? = null

    private val semaphore = Semaphore(MAX_CONCURRENT)
    private val jobs = ConcurrentHashMap<String, Job>()

    private val _running = MutableStateFlow<Set<String>>(emptySet())
    override val running: StateFlow<Set<String>> get() = _running

    override fun run(macro: MacroDefinition) {
        // Launched LAZILY so nothing actually executes until we know this call won the race to
        // claim macro.id -- otherwise two near-simultaneous run() calls could both pass a plain
        // containsKey() check before either one records its Job, leaving two coroutines running
        // the same macro while jobs[macro.id] only ever holds (and can only ever cancel) one of them.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            if (!semaphore.tryAcquire()) {
                eventLog.log(LogLevel.WARN, "Macro", "\"${macro.name}\" not started: too many macros already running")
                jobs.remove(macro.id)
                return@launch
            }
            _running.update { it + macro.id }
            try {
                executeSteps(macro.steps, depth = 0)
            } catch (e: Exception) {
                eventLog.log(LogLevel.ERROR, "Macro", "\"${macro.name}\" failed: ${e.message}")
            } finally {
                semaphore.release()
                _running.update { it - macro.id }
                jobs.remove(macro.id)
            }
        }
        if (jobs.putIfAbsent(macro.id, job) != null) {
            job.cancel() // already running; macros don't stack on themselves -- another call won the race
            return
        }
        job.start()
    }

    override fun cancel(macroId: String) {
        jobs.remove(macroId)?.cancel()
        _running.update { it - macroId }
    }

    override fun cancelAll() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        _running.value = emptySet()
    }

    private suspend fun executeSteps(steps: List<MacroStep>, depth: Int) {
        if (depth > com.titanmapper.core.model.MacroValidator.MAX_DEPTH) return
        var i = 0
        while (i < steps.size) {
            currentCoroutineContext().ensureActive()
            when (val step = steps[i]) {
                is MacroStep.PressKey -> dispatch(MappedAction.KeyInjection(step.key, KeyAction.DOWN))
                is MacroStep.ReleaseKey -> dispatch(MappedAction.KeyInjection(step.key, KeyAction.UP))
                is MacroStep.ClickMouse -> {
                    dispatch(MappedAction.MouseInjection.ButtonPress(step.button, KeyAction.DOWN))
                    delay(MIN_CLICK_HOLD_MS)
                    dispatch(MappedAction.MouseInjection.ButtonPress(step.button, KeyAction.UP))
                }
                is MacroStep.MoveMouse -> dispatch(MappedAction.MouseInjection.Move(step.deltaX, step.deltaY))
                is MacroStep.Delay -> delay(step.millis.coerceIn(0, com.titanmapper.core.model.MacroValidator.MAX_DELAY_MILLIS))
                is MacroStep.LoopStart -> {
                    val body = collectLoopBody(steps, i + 1)
                    val count = step.count
                    if (count == null) {
                        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                            executeSteps(body, depth + 1)
                            delay(MIN_LOOP_DELAY_MS) // guarantees a cancellation point even for empty/instant bodies
                        }
                    } else {
                        repeat(count) {
                            currentCoroutineContext().ensureActive()
                            executeSteps(body, depth + 1)
                        }
                    }
                    i += body.size + 1 // skip past the matching LoopEnd
                }
                MacroStep.LoopEnd -> Unit // reached only if malformed; collectLoopBody normally consumes these
                is MacroStep.ConditionalIf -> {
                    val branch = if (evaluate(step.condition)) step.thenSteps else step.elseSteps
                    executeSteps(branch, depth + 1)
                }
            }
            i++
        }
    }

    /** Returns the steps strictly between a LoopStart and its matching LoopEnd, respecting nesting. */
    private fun collectLoopBody(steps: List<MacroStep>, from: Int): List<MacroStep> {
        var depth = 1
        val body = ArrayList<MacroStep>()
        var idx = from
        while (idx < steps.size && depth > 0) {
            when (steps[idx]) {
                is MacroStep.LoopStart -> depth++
                MacroStep.LoopEnd -> depth--
                else -> Unit
            }
            if (depth > 0) body += steps[idx]
            idx++
        }
        return body
    }

    private fun evaluate(condition: MacroCondition): Boolean = when (condition) {
        is MacroCondition.ForegroundAppIs -> foregroundAppTracker.foregroundPackage.value == condition.packageName
        is MacroCondition.ToggleIsActive -> toggleStateProvider(condition.bindingId)
        is MacroCondition.DeviceIsConnected -> deviceConnectedProvider(condition.deviceFingerprintId)
        is MacroCondition.Not -> !evaluate(condition.condition)
    }

    private fun dispatch(action: MappedAction) {
        activeSink?.dispatch(action, DispatchPhase.ONCE)
    }

    private companion object {
        const val MAX_CONCURRENT = 4
        const val MIN_CLICK_HOLD_MS = 16L
        const val MIN_LOOP_DELAY_MS = 1L // Architecture §15.5: guards against a zero-delay infinite loop pegging a core
    }
}
