package com.titanmapper.service.engine

import com.titanmapper.core.model.ActionMode
import com.titanmapper.core.model.Binding
import com.titanmapper.core.model.InputTrigger
import com.titanmapper.core.model.KeyAction
import com.titanmapper.core.model.KeyCode
import com.titanmapper.core.model.MappedAction
import com.titanmapper.core.model.MappingProfile
import com.titanmapper.core.model.ModifierKey
import com.titanmapper.core.model.RawInputEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Resolves [RawInputEvent]s against the active [MappingProfile] and drives an
 * [OutputDispatchSink]. Pure Kotlin: no android.* imports, so it is unit-testable on the JVM.
 *
 * Threading: [handle] must be called from ONE consumer coroutine (the InputEventBus collector).
 * All mutable state below is confined to that caller; only [setProfile] may be called from
 * another thread, and it publishes an immutable [BindingIndex] through a @Volatile field.
 *
 * Responsibilities:
 *  - tracks pressed keys/modifiers (ignoring OS auto-repeat key-downs)
 *  - resolves combos (most specific modifier set wins), single keys, mouse buttons, scroll, sequences
 *  - runs the HOLD / TOGGLE / TAP / REPEAT state machine per active binding
 *  - guarantees release: a binding captured at key-down is released at the matching key-up even if
 *    modifiers changed in between (otherwise a "stuck" injected key results)
 */
class MappingEngine(
    private val sink: OutputDispatchSink,
    private val scope: CoroutineScope,
    /** Called for every event that produced (or failed to produce) an action; feeds the event logger. */
    private val onResolved: (ResolvedEvent) -> Unit = {}
) {
    data class ResolvedEvent(
        val binding: Binding,
        val phase: DispatchPhase,
        val accepted: Boolean,
        val latencyNanos: Long
    )

    @Volatile
    private var index: BindingIndex = BindingIndex.EMPTY

    // --- state confined to the single consumer coroutine ---
    private val pressedKeys = HashSet<KeyCode>()
    private val activeBindings = HashMap<Any, Binding>()   // source (KeyCode|MouseButton) -> binding captured at down
    private val toggleState = HashMap<String, Boolean>()   // bindingId -> currently on
    private val repeatJobs = HashMap<Any, Job>()
    private val sequenceBuffer = ArrayDeque<Pair<KeyCode, Long>>() // key, monotonicNanos

    /**
     * Swap the active profile. Only the immutable index is replaced (safe from any thread).
     * The caller should invoke [releaseAll] from the consumer coroutine first so nothing held
     * under the old profile stays stuck.
     */
    fun setProfile(profile: MappingProfile?) {
        index = if (profile == null) BindingIndex.EMPTY else BindingIndex.build(profile)
    }

    /**
     * Release every held/toggled/repeating action. Call before switching profiles or when the
     * capture tier is demoted/torn down, so no injected key is ever left stuck down.
     */
    fun releaseAll() {
        repeatJobs.values.forEach { it.cancel() }
        repeatJobs.clear()
        for ((_, binding) in activeBindings) {
            if (binding.mode == ActionMode.HOLD) sink.dispatch(binding.action, DispatchPhase.UP)
        }
        for ((id, on) in toggleState) {
            if (on) index.byId[id]?.let { sink.dispatch(it.action, DispatchPhase.UP) }
        }
        activeBindings.clear()
        toggleState.clear()
        pressedKeys.clear()
        sequenceBuffer.clear()
    }

    /**
     * Process one event.
     * @return true if the event was consumed by a binding and should NOT also reach the OS/app
     * (only meaningful on tiers that can swallow events: Accessibility onKeyEvent, Root EVIOCGRAB).
     */
    fun handle(event: RawInputEvent): Boolean = when (event) {
        is RawInputEvent.Key -> handleKey(event)
        is RawInputEvent.Button -> handleButton(event)
        is RawInputEvent.Scroll -> handleScroll(event)
        is RawInputEvent.DeviceConnectionChanged -> {
            if (!event.connected) releaseAll()
            false
        }
        is RawInputEvent.Motion -> false // pointer motion is handled by the pointer pipeline, not bindings
    }

    private fun handleKey(e: RawInputEvent.Key): Boolean {
        val key = e.keyCode
        if (e.action == KeyAction.DOWN) {
            val alreadyDown = !pressedKeys.add(key)
            if (alreadyDown) {
                // OS auto-repeat: swallow if we own this key, never re-trigger the binding.
                return activeBindings.containsKey(key)
            }
            val binding = resolveKeyDown(key, e.monotonicNanos) ?: return false
            activeBindings[key] = binding
            return onDown(key, binding, e.monotonicNanos)
        } else {
            pressedKeys.remove(key)
            val binding = activeBindings.remove(key) ?: return false
            return onUp(key, binding, e.monotonicNanos)
        }
    }

    private fun handleButton(e: RawInputEvent.Button): Boolean {
        val source = e.button
        if (e.action == KeyAction.DOWN) {
            val binding = index.mouse[e.button]?.takeIf { it.isEnabled } ?: return false
            activeBindings[source] = binding
            return onDown(source, binding, e.monotonicNanos)
        }
        val binding = activeBindings.remove(source) ?: return false
        return onUp(source, binding, e.monotonicNanos)
    }

    private fun handleScroll(e: RawInputEvent.Scroll): Boolean {
        val binding = index.scroll[e.direction]?.takeIf { it.isEnabled } ?: return false
        // A wheel tick has no "up": always one-shot, whatever the configured mode.
        dispatch(binding, DispatchPhase.ONCE, e.monotonicNanos)
        return true
    }

    /** Priority: sequence completion > combo (most modifiers) > single key. */
    private fun resolveKeyDown(key: KeyCode, nanos: Long): Binding? {
        if (!key.isModifier()) {
            recordSequence(key, nanos)
            matchSequence(nanos)?.let { return it }
            val held = heldModifiers()
            index.combos[key]?.let { candidates ->
                // candidates are pre-sorted by modifier count, descending
                for (c in candidates) if (held.containsAll(c.modifiers) && c.binding.isEnabled) return c.binding
            }
        }
        return index.singleKeys[key]?.takeIf { it.isEnabled }
    }

    private fun recordSequence(key: KeyCode, nanos: Long) {
        sequenceBuffer.addLast(key to nanos)
        while (sequenceBuffer.size > MAX_SEQUENCE_LENGTH) sequenceBuffer.removeFirst()
    }

    private fun matchSequence(nanos: Long): Binding? {
        for (s in index.sequences) {
            val keys = s.trigger.keys
            if (sequenceBuffer.size < keys.size) continue
            val tail = sequenceBuffer.toList().takeLast(keys.size)
            if (tail.map { it.first } != keys) continue
            val maxGapNanos = s.trigger.maxGapMillis * 1_000_000L
            val gapsOk = tail.zipWithNext().all { (a, b) -> b.second - a.second <= maxGapNanos }
            if (gapsOk && nanos - tail.last().second <= maxGapNanos) {
                sequenceBuffer.clear() // consume so "g g g" doesn't fire twice
                return s.binding
            }
        }
        return null
    }

    private fun onDown(source: Any, binding: Binding, nanos: Long): Boolean {
        when (binding.mode) {
            ActionMode.TAP -> dispatch(binding, DispatchPhase.ONCE, nanos)
            ActionMode.HOLD -> dispatch(binding, DispatchPhase.DOWN, nanos)
            ActionMode.TOGGLE -> {
                val nowOn = !(toggleState[binding.id] ?: false)
                toggleState[binding.id] = nowOn
                dispatch(binding, if (nowOn) DispatchPhase.DOWN else DispatchPhase.UP, nanos)
            }
            ActionMode.REPEAT -> {
                dispatch(binding, DispatchPhase.ONCE, nanos)
                repeatJobs.remove(source)?.cancel()
                val interval = binding.repeatIntervalMillis.coerceAtLeast(MIN_REPEAT_INTERVAL_MS)
                repeatJobs[source] = scope.launch {
                    while (isActive) {
                        delay(interval)
                        dispatch(binding, DispatchPhase.ONCE, System.nanoTime())
                    }
                }
            }
        }
        return true
    }

    private fun onUp(source: Any, binding: Binding, nanos: Long): Boolean {
        when (binding.mode) {
            ActionMode.HOLD -> dispatch(binding, DispatchPhase.UP, nanos)
            ActionMode.REPEAT -> repeatJobs.remove(source)?.cancel()
            ActionMode.TAP, ActionMode.TOGGLE -> Unit
        }
        return true
    }

    private fun dispatch(binding: Binding, phase: DispatchPhase, eventNanos: Long) {
        if (binding.action is MappedAction.NoAction) return
        val accepted = sink.dispatch(binding.action, phase)
        onResolved(ResolvedEvent(binding, phase, accepted, System.nanoTime() - eventNanos))
    }

    private fun heldModifiers(): Set<ModifierKey> {
        val out = HashSet<ModifierKey>(4)
        for (k in pressedKeys) k.toModifier()?.let(out::add)
        return out
    }

    private companion object {
        const val MAX_SEQUENCE_LENGTH = 8
        const val MIN_REPEAT_INTERVAL_MS = 10L // protects the sink and battery from absurd configs
    }
}

// ---- Index ---------------------------------------------------------------------------------

/** Immutable lookup tables built once per profile swap so per-event resolution is O(1). */
internal class BindingIndex private constructor(
    val singleKeys: Map<KeyCode, Binding>,
    val combos: Map<KeyCode, List<ComboEntry>>,
    val sequences: List<SequenceEntry>,
    val mouse: Map<com.titanmapper.core.model.MouseButton, Binding>,
    val scroll: Map<com.titanmapper.core.model.ScrollDirection, Binding>,
    val byId: Map<String, Binding>
) {
    class ComboEntry(val modifiers: Set<ModifierKey>, val binding: Binding)
    class SequenceEntry(val trigger: InputTrigger.Sequence, val binding: Binding)

    companion object {
        val EMPTY = BindingIndex(emptyMap(), emptyMap(), emptyList(), emptyMap(), emptyMap(), emptyMap())

        fun build(profile: MappingProfile): BindingIndex {
            val single = HashMap<KeyCode, Binding>()
            val combos = HashMap<KeyCode, MutableList<ComboEntry>>()
            val seqs = ArrayList<SequenceEntry>()
            val mouse = HashMap<com.titanmapper.core.model.MouseButton, Binding>()
            val scroll = HashMap<com.titanmapper.core.model.ScrollDirection, Binding>()
            for (b in profile.bindings) {
                when (val t = b.trigger) {
                    is InputTrigger.SingleKey -> single[t.key] = b
                    is InputTrigger.Combo -> combos.getOrPut(t.key) { ArrayList() }.add(ComboEntry(t.modifiers, b))
                    is InputTrigger.Sequence -> if (t.keys.size >= 2) seqs.add(SequenceEntry(t, b))
                    is InputTrigger.Mouse -> mouse[t.button] = b
                    is InputTrigger.Scroll -> scroll[t.direction] = b
                }
            }
            combos.values.forEach { list -> list.sortByDescending { it.modifiers.size } }
            // Longer sequences first so "g g g" beats "g g".
            seqs.sortByDescending { it.trigger.keys.size }
            return BindingIndex(single, combos, seqs, mouse, scroll, profile.bindings.associateBy { it.id })
        }
    }
}

// ---- KeyCode helpers -----------------------------------------------------------------------

internal fun KeyCode.isModifier(): Boolean = toModifier() != null

internal fun KeyCode.toModifier(): ModifierKey? = when (this) {
    KeyCode.SHIFT_LEFT, KeyCode.SHIFT_RIGHT -> ModifierKey.SHIFT
    KeyCode.CTRL_LEFT, KeyCode.CTRL_RIGHT -> ModifierKey.CTRL
    KeyCode.ALT_LEFT, KeyCode.ALT_RIGHT -> ModifierKey.ALT
    KeyCode.META_LEFT, KeyCode.META_RIGHT -> ModifierKey.META
    else -> null
}
