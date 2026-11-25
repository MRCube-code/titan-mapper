package com.titanmapper.service.input

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.titanmapper.core.model.CaptureTier
import com.titanmapper.core.model.DeviceClass
import com.titanmapper.core.model.DeviceFingerprint
import com.titanmapper.core.model.KeyAction
import com.titanmapper.core.model.MouseButton
import com.titanmapper.core.model.RawInputEvent
import com.titanmapper.core.model.ScrollDirection
import com.titanmapper.service.api.ActivityInputBridge
import com.titanmapper.service.api.ServiceController
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Standard-tier capture (Architecture §4.2). Only sees events while Titan Mapper's own window
 * has focus — this is an OS guarantee we cannot and do not try to bypass on this tier. The
 * Activity forwards its dispatchKeyEvent/dispatchGenericMotionEvent calls here.
 *
 * Two real bugs fixed here:
 *
 * 1. [onKeyEvent] used to return `true` (consume) for every recognized key unconditionally,
 * regardless of whether anything was actually bound to it -- unlike the Accessibility tier,
 * which correctly gates consumption on [ServiceController.isKeyBound]. With a physical keyboard
 * attached, that meant literally no keystroke ever reached Titan Mapper's own UI (an
 * OutlinedTextField renaming a profile, a search box, etc.) whenever the app had focus: every
 * key was swallowed here first. Now mirrors the Accessibility tier's rule -- only an actually
 * bound key is consumed; everything else passes through to the app's own UI untouched.
 *
 * 2. Physical mouse buttons were never captured at all. [onGenericMotionEvent] only ever handled
 * scroll and relative hover-motion; the real button-click events -- [MotionEvent.ACTION_BUTTON_PRESS]
 * / [MotionEvent.ACTION_BUTTON_RELEASE], which is how Android reports right-click, middle-click,
 * and M4/M5 side buttons -- were never read, and the one method that *could* publish a button
 * event ([onButtonEvent], now removed) was never called by anything. Handled below via
 * [MotionEvent.getActionButton].
 *
 * A further M4/M5 wrinkle: many mice report their side buttons as literal `KeyEvent`s with
 * keyCode `KEYCODE_BACK`/`KEYCODE_FORWARD` instead of button-press MotionEvents -- not something
 * [AndroidKeyCodeTranslator] can map, since `KEYCODE_BACK` is also the phone's real Back action
 * (hardware key, 3-button nav, back gesture). Swallowing every `KEYCODE_BACK` globally would
 * break navigation app-wide, so this is disambiguated by the originating device's reported
 * source: only a `KEYCODE_BACK`/`KEYCODE_FORWARD` that actually came from a mouse-sourced device
 * is treated as the M4/M5 mouse button it is; everything else is left as ordinary Back/Forward
 * navigation and never touched.
 */
@Singleton
class ActivityInputBridgeImpl @Inject constructor(
    private val bus: InputEventBus,
    private val controller: ServiceController
) : ActivityInputBridge {

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.repeatCount > 0 && event.action == KeyEvent.ACTION_DOWN) {
            // OS auto-repeat: let the engine's own repeat state machine own this, don't double-feed it.
            return false
        }

        if (isMouseSourced(event.device) && (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_FORWARD)) {
            val button = if (event.keyCode == KeyEvent.KEYCODE_BACK) MouseButton.BACK else MouseButton.FORWARD
            bus.publish(
                RawInputEvent.Button(
                    deviceFingerprint = fingerprint(event.device),
                    monotonicNanos = System.nanoTime(),
                    sourceTier = CaptureTier.STANDARD,
                    button = button,
                    action = if (event.action == KeyEvent.ACTION_DOWN) KeyAction.DOWN else KeyAction.UP
                )
            )
            // Never consume this one: a real hardware/gesture Back press must keep working even
            // when a mouse happens to also be connected, and the mapped *output* (if any) is
            // produced downstream by the dispatch sink, not by swallowing the raw OS key here.
            return false
        }

        val keyCode = AndroidKeyCodeTranslator.fromAndroid(event.keyCode)
        if (keyCode == com.titanmapper.core.model.KeyCode.UNKNOWN) return false

        val action = if (event.action == KeyEvent.ACTION_DOWN) KeyAction.DOWN else KeyAction.UP
        bus.publish(
            RawInputEvent.Key(
                deviceFingerprint = fingerprint(event.device),
                monotonicNanos = System.nanoTime(),
                sourceTier = CaptureTier.STANDARD,
                keyCode = keyCode,
                action = action
            )
        )
        // Only consume a key an active, enabled binding actually owns -- otherwise every
        // recognized keystroke (ordinary typing into this app's own UI) would be eaten the
        // moment a physical keyboard is attached, even with nothing bound to it.
        return controller.isKeyBound(keyCode)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        var consumed = false
        if (event.action == MotionEvent.ACTION_SCROLL) {
            val vertical = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            val horizontal = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
            if (vertical != 0f) {
                bus.publish(scrollEvent(event, if (vertical > 0) ScrollDirection.UP else ScrollDirection.DOWN, vertical))
                consumed = true
            }
            if (horizontal != 0f) {
                bus.publish(scrollEvent(event, if (horizontal > 0) ScrollDirection.RIGHT else ScrollDirection.LEFT, horizontal))
                consumed = true
            }
        } else if (event.action == MotionEvent.ACTION_HOVER_MOVE &&
            event.source and InputDevice.SOURCE_MOUSE_RELATIVE == InputDevice.SOURCE_MOUSE_RELATIVE
        ) {
            bus.publish(
                RawInputEvent.Motion(
                    deviceFingerprint = fingerprint(event.device),
                    monotonicNanos = System.nanoTime(),
                    sourceTier = CaptureTier.STANDARD,
                    deltaX = event.getAxisValue(MotionEvent.AXIS_RELATIVE_X),
                    deltaY = event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)
                )
            )
            consumed = true
        } else if (event.action == MotionEvent.ACTION_BUTTON_PRESS || event.action == MotionEvent.ACTION_BUTTON_RELEASE) {
            // Right-click, middle-click, and M4/M5 (on mice that report them this way rather than
            // as KEYCODE_BACK/FORWARD) arrive here, never through onKeyEvent or a touch ACTION_DOWN.
            val button = mouseButtonFor(event.actionButton)
            if (button != null) {
                bus.publish(
                    RawInputEvent.Button(
                        deviceFingerprint = fingerprint(event.device),
                        monotonicNanos = System.nanoTime(),
                        sourceTier = CaptureTier.STANDARD,
                        button = button,
                        action = if (event.action == MotionEvent.ACTION_BUTTON_PRESS) KeyAction.DOWN else KeyAction.UP
                    )
                )
                consumed = true
            }
        }
        return consumed
    }

    private fun isMouseSourced(device: InputDevice?): Boolean {
        if (device == null) return false
        return device.supportsSource(InputDevice.SOURCE_MOUSE) || device.supportsSource(InputDevice.SOURCE_MOUSE_RELATIVE)
    }

    private fun mouseButtonFor(actionButton: Int): MouseButton? = when (actionButton) {
        MotionEvent.BUTTON_PRIMARY -> MouseButton.LEFT
        MotionEvent.BUTTON_SECONDARY -> MouseButton.RIGHT
        MotionEvent.BUTTON_TERTIARY -> MouseButton.MIDDLE
        MotionEvent.BUTTON_BACK -> MouseButton.BACK
        MotionEvent.BUTTON_FORWARD -> MouseButton.FORWARD
        else -> null
    }

    private fun scrollEvent(event: MotionEvent, direction: ScrollDirection, amount: Float) = RawInputEvent.Scroll(
        deviceFingerprint = fingerprint(event.device),
        monotonicNanos = System.nanoTime(),
        sourceTier = CaptureTier.STANDARD,
        direction = direction,
        amount = kotlin.math.abs(amount)
    )

    private fun fingerprint(device: InputDevice?): DeviceFingerprint {
        if (device == null) return UNKNOWN_DEVICE
        return DeviceFingerprint(
            stableId = InputDeviceMonitor.stableId(device.vendorId, device.productId, device.name ?: "unknown"),
            vendorId = device.vendorId,
            productId = device.productId,
            deviceName = device.name ?: "Unknown device",
            deviceClass = DeviceClass.UNKNOWN
        )
    }

    private companion object {
        val UNKNOWN_DEVICE = DeviceFingerprint("unknown", 0, 0, "Unknown device", DeviceClass.UNKNOWN)
    }
}
