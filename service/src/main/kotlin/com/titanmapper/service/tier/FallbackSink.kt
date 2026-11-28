package com.titanmapper.service.tier

import com.titanmapper.core.model.CaptureTier
import com.titanmapper.core.model.MappedAction
import com.titanmapper.service.engine.DispatchPhase
import com.titanmapper.service.engine.OutputDispatchSink

/**
 * Lets the active tier keep owning capture while a helper sink (Shizuku) performs the one thing
 * the active tier cannot: [MappedAction.KeyInjection].
 *
 * Why this exists: the pipeline runs exactly one tier. On the Accessibility tier the key you
 * bound (say G) is swallowed system-wide -- that tier is very good at that -- but its sink
 * honestly rejects KeyInjection, so the mapped output (F) was never sent and *nothing* appeared
 * in the target app. With this wrapper, Accessibility keeps swallowing the original and Shizuku
 * types the replacement.
 *
 * Deliberately limited to KeyInjection. Pointer-motion and mouse-button actions are NOT delegated:
 * the Shizuku mouse path injects at an unknown position and must not receive a stream of motion
 * deltas from a tier that never used it for that.
 */
class FallbackSink(
    private val primary: OutputDispatchSink,
    private val helper: OutputDispatchSink,
    private val helperReady: () -> Boolean
) : OutputDispatchSink {

    override val tier: CaptureTier get() = primary.tier

    override fun dispatch(action: MappedAction, phase: DispatchPhase): Boolean = when {
        primary.supports(action) -> primary.dispatch(action, phase)
        delegates(action) -> helper.dispatch(action, phase)
        else -> false
    }

    override fun supports(action: MappedAction): Boolean = primary.supports(action) || delegates(action)

    private fun delegates(action: MappedAction): Boolean =
        action is MappedAction.KeyInjection && helperReady() && helper.supports(action)
}
