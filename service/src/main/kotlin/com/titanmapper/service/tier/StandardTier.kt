package com.titanmapper.service.tier

import com.titanmapper.core.model.CaptureTier
import com.titanmapper.core.model.MappedAction
import com.titanmapper.service.engine.DispatchPhase
import com.titanmapper.service.engine.OutputDispatchSink
import com.titanmapper.service.input.InputDeviceMonitor
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Standard tier (Architecture §4.2). Capture is the [com.titanmapper.service.api.ActivityInputBridge]
 * wired up by MainActivity while Titan Mapper has window focus — there is no system-wide
 * capture to start here, so [start] only begins device enumeration for the Devices screen.
 *
 * Dispatch is honestly limited to what this tier can actually do without any special permission:
 * visual-only feedback inside Titan Mapper's own UI (a tap/drag intent the overlay preview can
 * render) and running macros composed of such actions. Anything that needs system-wide
 * injection is rejected with `accepted = false`, surfaced to the user via the event logger.
 */
@Singleton
class StandardTier @Inject constructor(
    private val deviceMonitor: InputDeviceMonitor
) : TierBackend {

    override val tier = CaptureTier.STANDARD

    private val _dispatched = MutableSharedFlow<Pair<MappedAction, DispatchPhase>>(extraBufferCapacity = 16)
    /** Consumed by the in-app overlay preview to visualize what *would* happen on this tier. */
    val dispatched: SharedFlow<Pair<MappedAction, DispatchPhase>> get() = _dispatched

    override val sink = object : OutputDispatchSink {
        override val tier = CaptureTier.STANDARD

        override fun dispatch(action: MappedAction, phase: DispatchPhase): Boolean {
            if (!supports(action)) return false
            _dispatched.tryEmit(action to phase)
            return true
        }

        override fun supports(action: MappedAction): Boolean = when (action) {
            is MappedAction.TouchTap, is MappedAction.TouchDrag, is MappedAction.MacroRun -> true
            else -> false
        }
    }

    override suspend fun start(): Boolean {
        deviceMonitor.start()
        return true
    }

    override fun stop() {
        deviceMonitor.stop()
    }

    /** Always "healthy" — it never relies on a revocable grant. */
    override fun isHealthy(): Boolean = true
}
