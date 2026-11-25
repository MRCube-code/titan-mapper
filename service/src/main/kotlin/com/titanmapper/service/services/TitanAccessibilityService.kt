package com.titanmapper.service.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.graphics.Path
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.titanmapper.core.model.CaptureTier
import com.titanmapper.core.model.DeviceClass
import com.titanmapper.core.model.DeviceFingerprint
import com.titanmapper.core.model.KeyAction
import com.titanmapper.service.api.ServiceController
import com.titanmapper.service.input.AndroidKeyCodeTranslator
import com.titanmapper.service.input.InputDeviceMonitor
import com.titanmapper.service.input.InputEventBus
import com.titanmapper.core.model.RawInputEvent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Accessibility tier (Architecture §4.3). [onKeyEvent] with
 * [AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS] is the only no-root/no-ADB path to
 * true system-wide key interception on stock Android — it runs before the foreground app sees
 * the key. Returning true CONSUMES the key everywhere, which is how this tier "captures" input
 * at all, but [onKeyEvent] only ever does that for a key [ServiceController.isKeyBound] says an
 * active, enabled binding actually owns -- every other recognized key (i.e. ordinary typing) is
 * returned false and reaches the foreground app untouched, same as if this service didn't exist.
 * It cannot, however, inject KeyEvents into other apps (gesture injection only), and
 * foreground-package tracking comes from window-state accessibility events, not from any
 * reserved API.
 */
@AndroidEntryPoint
class TitanAccessibilityService : AccessibilityService() {

    @Inject lateinit var bus: InputEventBus
    @Inject lateinit var controller: ServiceController

    /** Android keyCodes whose ACTION_DOWN we consumed; onKeyEvent runs on the main thread only,
     *  so no extra synchronization is needed to touch this from there. */
    private val consumedKeys = HashSet<Int>()

    /** Same tracking as [consumedKeys], kept separate for the mouse-button-as-KEYCODE_BACK/FORWARD
     *  case below -- see [onKeyEvent]'s KDoc. */
    private val consumedMouseButtonKeys = HashSet<Int>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo?.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        }
        consumedKeys.clear() // a freshly (re)connected service never already owns anything
        consumedMouseButtonKeys.clear()
        instance = this
        _connected.value = true
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // Many mice report M4/M5 as a literal KEYCODE_BACK/KEYCODE_FORWARD KeyEvent rather than a
        // button-press MotionEvent (AccessibilityService never receives generic motion events at
        // all, so that path is unavailable here regardless -- a real, honest limitation of this
        // tier, not a bug). KEYCODE_BACK is also the phone's real Back action, so this is only
        // ever treated as a mouse button when the originating device actually reports a mouse
        // source; every other KEYCODE_BACK/FORWARD (hardware key, nav bar, gesture) is left alone.
        val device = android.view.InputDevice.getDevice(event.deviceId)
        val fromMouse = device != null && (
            device.supportsSource(android.view.InputDevice.SOURCE_MOUSE) ||
                device.supportsSource(android.view.InputDevice.SOURCE_MOUSE_RELATIVE)
            )
        if (fromMouse && (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_FORWARD)) {
            return handleMouseButtonAsKeyEvent(event)
        }

        val keyCode = AndroidKeyCodeTranslator.fromAndroid(event.keyCode)
        if (keyCode == com.titanmapper.core.model.KeyCode.UNKNOWN) return false

        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount > 0) {
                // OS auto-repeat: keep consuming only while we're already holding this key down.
                return event.keyCode in consumedKeys
            }
            // This tier's whole value proposition is swallowing a key system-wide, but only a key
            // an active, enabled binding actually owns -- otherwise every recognized keystroke
            // (basically all normal typing) would be eaten the moment this tier is turned on,
            // even in apps that have nothing to do with Titan Mapper.
            if (!controller.isKeyBound(keyCode)) return false
            consumedKeys += event.keyCode
        } else {
            // ACTION_UP: consume iff we consumed the matching DOWN. Checking isKeyBound() again
            // here would be wrong -- the binding can change (profile swap, toggle off) between a
            // key's down and up, and an unmatched up/down pair reaching the foreground app (or a
            // down we consumed with its up let through) is its own "stuck key" class of bug.
            if (event.keyCode !in consumedKeys) return false
            consumedKeys -= event.keyCode
        }

        bus.publish(
            RawInputEvent.Key(
                deviceFingerprint = fingerprintFor(event.deviceId),
                monotonicNanos = System.nanoTime(),
                sourceTier = CaptureTier.ACCESSIBILITY,
                keyCode = keyCode,
                action = if (event.action == KeyEvent.ACTION_DOWN) KeyAction.DOWN else KeyAction.UP
            )
        )
        return true
    }

    private fun handleMouseButtonAsKeyEvent(event: KeyEvent): Boolean {
        val button = if (event.keyCode == KeyEvent.KEYCODE_BACK) com.titanmapper.core.model.MouseButton.BACK
        else com.titanmapper.core.model.MouseButton.FORWARD

        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount > 0) return event.keyCode in consumedMouseButtonKeys
            if (!controller.isButtonBound(button)) return false
            consumedMouseButtonKeys += event.keyCode
        } else {
            if (event.keyCode !in consumedMouseButtonKeys) return false
            consumedMouseButtonKeys -= event.keyCode
        }

        bus.publish(
            RawInputEvent.Button(
                deviceFingerprint = fingerprintFor(event.deviceId),
                monotonicNanos = System.nanoTime(),
                sourceTier = CaptureTier.ACCESSIBILITY,
                button = button,
                action = if (event.action == KeyEvent.ACTION_DOWN) KeyAction.DOWN else KeyAction.UP
            )
        )
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            _foregroundPackage.value = event.packageName?.toString()
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) instance = null
        _connected.value = false
    }

    /** Touch-gesture dispatch — the only output path available on this tier (Architecture §4.3). */
    @SuppressLint("NewApi")
    fun dispatchTap(x: Float, y: Float, durationMillis: Long): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, durationMillis.coerceAtLeast(MIN_GESTURE_MS))
        val gesture = android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    fun dispatchDrag(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMillis: Long): Boolean {
        val path = Path().apply { moveTo(fromX, fromY); lineTo(toX, toY) }
        val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, durationMillis.coerceAtLeast(MIN_GESTURE_MS))
        val gesture = android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    fun dispatchGlobalAction(action: Int): Boolean = performGlobalAction(action)

    private fun fingerprintFor(deviceId: Int): DeviceFingerprint {
        val device = android.view.InputDevice.getDevice(deviceId)
        return if (device == null) UNKNOWN_DEVICE else DeviceFingerprint(
            stableId = InputDeviceMonitor.stableId(device.vendorId, device.productId, device.name ?: "unknown"),
            vendorId = device.vendorId,
            productId = device.productId,
            deviceName = device.name ?: "Unknown device",
            deviceClass = DeviceClass.UNKNOWN
        )
    }

    companion object {
        private const val MIN_GESTURE_MS = 1L
        private val UNKNOWN_DEVICE = DeviceFingerprint("unknown", 0, 0, "Unknown device", DeviceClass.UNKNOWN)

        /** Nullable: the system creates/destroys this service; the tier backend must tolerate it not existing yet. */
        @Volatile var instance: TitanAccessibilityService? = null
            private set

        private val _connected = MutableStateFlow(false)
        val connected: StateFlow<Boolean> get() = _connected

        private val _foregroundPackage = MutableStateFlow<String?>(null)
        val foregroundPackage: StateFlow<String?> get() = _foregroundPackage
    }
}
