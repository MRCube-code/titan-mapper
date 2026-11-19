package com.titanmapper.service.macro

import com.titanmapper.core.model.KeyAction
import com.titanmapper.core.model.MacroStep
import com.titanmapper.core.model.MacroValidator
import com.titanmapper.core.model.RawInputEvent
import com.titanmapper.service.api.MacroRecorder
import com.titanmapper.service.api.RecorderState
import com.titanmapper.service.input.InputEventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Records live input into a clean [MacroStep] list (Architecture §10). Subscribes to the same
 * [InputEventBus] the engine consumes, so it sees exactly what the user's devices produced.
 * Near-identical delays (within [JITTER_TOLERANCE_MS]) are collapsed and rounded to the nearest
 * [DELAY_GRANULARITY_MS] so a human recording doesn't produce dozens of near-duplicate Delay steps.
 */
@Singleton
class MacroRecorderImpl @Inject constructor(
    private val bus: InputEventBus
) : MacroRecorder {

    private val _state = MutableStateFlow(RecorderState())
    override val state: StateFlow<RecorderState> get() = _state

    private val mutex = Mutex()
    private var job: Job? = null
    private val steps = ArrayList<MacroStep>()
    private var lastEventNanos: Long = 0

    override fun start() {
        if (job != null) return
        steps.clear()
        lastEventNanos = 0
        _state.value = RecorderState(recording = true, capturedEvents = 0)
        job = CoroutineScope(Dispatchers.Default).launch {
            bus.events.collect { event -> onEvent(event) }
        }
    }

    override suspend fun stop(): List<MacroStep> = mutex.withLock {
        job?.cancel()
        job = null
        _state.value = RecorderState(recording = false, capturedEvents = steps.size)
        steps.toList()
    }

    private suspend fun onEvent(event: RawInputEvent) {
        if (steps.size >= MacroValidator.MAX_TOTAL_STEPS) return
        mutex.withLock {
            appendDelayIfNeeded(event.monotonicNanos)
            when (event) {
                is RawInputEvent.Key -> steps += if (event.action == KeyAction.DOWN) {
                    MacroStep.PressKey(event.keyCode)
                } else {
                    MacroStep.ReleaseKey(event.keyCode)
                }
                is RawInputEvent.Button -> if (event.action == KeyAction.DOWN) steps += MacroStep.ClickMouse(event.button)
                is RawInputEvent.Motion -> steps += MacroStep.MoveMouse(event.deltaX, event.deltaY)
                else -> return@withLock // scroll/device-connection changes aren't recorded as macro steps
            }
            lastEventNanos = event.monotonicNanos
            _state.value = _state.value.copy(capturedEvents = steps.size)
        }
    }

    private fun appendDelayIfNeeded(nowNanos: Long) {
        if (lastEventNanos == 0L) return
        val gapMs = (nowNanos - lastEventNanos) / 1_000_000
        if (gapMs < JITTER_TOLERANCE_MS) return
        val rounded = ((gapMs / DELAY_GRANULARITY_MS) * DELAY_GRANULARITY_MS).coerceAtLeast(DELAY_GRANULARITY_MS)
        steps += MacroStep.Delay(rounded)
    }

    private companion object {
        const val JITTER_TOLERANCE_MS = 8L
        const val DELAY_GRANULARITY_MS = 10L
    }
}
