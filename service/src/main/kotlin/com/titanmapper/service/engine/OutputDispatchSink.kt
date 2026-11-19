package com.titanmapper.service.engine

import com.titanmapper.core.model.CaptureTier
import com.titanmapper.core.model.MappedAction

/**
 * Lifecycle phase of a dispatched action. Distinguishing DOWN/UP from ONCE lets a sink for
 * "hold W" emit a real key-down now and a key-up later, instead of faking it with a tap.
 */
enum class DispatchPhase { DOWN, UP, ONCE }

/**
 * Output side of the pipeline (Architecture §4.6). One implementation per permission tier:
 *  - Standard: gestures inside Titan Mapper's own overlay window
 *  - Accessibility: AccessibilityService.dispatchGesture / performGlobalAction
 *  - Shizuku: IInputManager.injectInputEvent
 *  - Root: uinput virtual HID device
 *
 * [dispatch] is intentionally NON-suspending and must return in microseconds: it is called on
 * the input hot path. Implementations queue work onto their own dispatcher (a bounded channel
 * drained by a dedicated coroutine) and return immediately.
 */
interface OutputDispatchSink {
    val tier: CaptureTier

    /**
     * @return true if the action was accepted for delivery; false if this tier cannot perform it
     * (e.g. [MappedAction.KeyInjection] on the Accessibility tier). The engine reports rejected
     * actions to the event logger so users see *why* a binding did nothing, rather than a silent failure.
     */
    fun dispatch(action: MappedAction, phase: DispatchPhase): Boolean

    /** Whether this sink can perform [action] at all — used by the UI to warn at binding-creation time. */
    fun supports(action: MappedAction): Boolean
}
