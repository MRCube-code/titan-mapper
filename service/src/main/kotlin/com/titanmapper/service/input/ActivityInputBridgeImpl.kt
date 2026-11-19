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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Standard-tier capture (Architecture §4.2). Only sees events while Titan Mapper's own window
 * has focus — this is an OS guarantee we cannot and do not try to bypass on this tier. The
 * Activity forwards its dispatchKeyEvent/dispatchGenericMotionEvent calls here.
 */
@Singleton
class ActivityInputBridgeImpl @Inject constructor(
    private val bus: InputEventBus
) : ActivityInputBridge {

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.repeatCount > 0 && event.action == KeyEvent.ACTION_DOWN) {
            // OS auto-repeat: let the engine's own repeat state machine own this, don't double-feed it.
            return false
        }
        val keyCode = AndroidKeyCodeTranslator.fromAndroid(event.keyCode)
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
        return keyCode != com.titanmapper.core.model.KeyCode.UNKNOWN
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
        }
        return consumed
    }

    /** Mouse button down/up as reported through [MotionEvent.getButtonState]; call from the Activity's onGenericMotionEvent for ACTION_BUTTON_PRESS/RELEASE. */
    fun onButtonEvent(event: MotionEvent, button: MouseButton, pressed: Boolean) {
        bus.publish(
            RawInputEvent.Button(
                deviceFingerprint = fingerprint(event.device),
                monotonicNanos = System.nanoTime(),
                sourceTier = CaptureTier.STANDARD,
                button = button,
                action = if (pressed) KeyAction.DOWN else KeyAction.UP
            )
        )
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
