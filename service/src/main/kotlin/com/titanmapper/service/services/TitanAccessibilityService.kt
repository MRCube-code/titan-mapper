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
 * the key. Returning true here CONSUMES the key everywhere, which is how this tier can "capture"
 * input at all. It cannot, however, inject KeyEvents into other apps (gesture injection only),
 * and foreground-package tracking comes from window-state accessibility events, not from any
 * reserved API.
 */
@AndroidEntryPoint
class TitanAccessibilityService : AccessibilityService() {

    @Inject lateinit var bus: InputEventBus

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo?.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        }
        instance = this
        _connected.value = true
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.repeatCount > 0 && event.action == KeyEvent.ACTION_DOWN) return isOwned(event.keyCode)
        val keyCode = AndroidKeyCodeTranslator.fromAndroid(event.keyCode)
        if (keyCode == com.titanmapper.core.model.KeyCode.UNKNOWN) return false
        bus.publish(
            RawInputEvent.Key(
                deviceFingerprint = fingerprintFor(event.deviceId),
                monotonicNanos = System.nanoTime(),
                sourceTier = CaptureTier.ACCESSIBILITY,
                keyCode = keyCode,
                action = if (event.action == KeyEvent.ACTION_DOWN) KeyAction.DOWN else KeyAction.UP
            )
        )
        return true // consume: this tier's whole value proposition is swallowing the key system-wide
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

    private fun isOwned(androidKeyCode: Int) = AndroidKeyCodeTranslator.fromAndroid(androidKeyCode) != com.titanmapper.core.model.KeyCode.UNKNOWN

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
