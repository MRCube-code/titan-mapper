package com.titanmapper.service.input

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import com.titanmapper.core.common.ApplicationScope
import com.titanmapper.core.common.DataResult
import com.titanmapper.core.data.repository.DeviceRepository
import com.titanmapper.core.model.DeviceClass
import com.titanmapper.core.model.DeviceFingerprint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Enumerates and watches connected keyboards/mice via `InputManager` (Standard-tier capability,
 * Architecture §4.2). Available on every tier — even Shizuku/Root use this for device
 * identity, only their *capture path* differs.
 */
@Singleton
class InputDeviceMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deviceRepository: DeviceRepository,
    @ApplicationScope private val scope: CoroutineScope
) {
    private val inputManager = context.getSystemService(Context.INPUT_SERVICE) as InputManager

    /**
     * Android's deviceId is only valid while the device is attached, so [onInputDeviceRemoved]
     * can't look anything up about it -- but [DeviceRepository.onDeviceDisconnected] needs the
     * stable fingerprint id, not the ephemeral deviceId. Recorded at add/change time so it's still
     * available once the device is actually gone.
     */
    private val deviceIdToStableId = ConcurrentHashMap<Int, String>()

    private val listener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = handle(deviceId)
        override fun onInputDeviceChanged(deviceId: Int) = handle(deviceId)
        override fun onInputDeviceRemoved(deviceId: Int) {
            // Previously a no-op: DeviceRepository.onDeviceDisconnected() was never called from
            // anywhere, so a device marked "connected" here never went back to "disconnected" on a
            // normal unplug -- only a process restart (which resets the in-memory connected set)
            // ever cleared it. The Devices screen and MacroCondition.DeviceIsConnected both read
            // that same connected set, so both were stuck believing an unplugged device was still
            // there.
            val stableId = deviceIdToStableId.remove(deviceId) ?: return
            deviceRepository.onDeviceDisconnected(stableId)
        }
    }

    /** Main-thread Handler, used explicitly below -- see [start]'s KDoc for why `null` isn't safe here. */
    private val mainHandler = Handler(Looper.getMainLooper())

    fun start() {
        // A null Handler here makes InputManager fall back to Looper.myLooper() on whichever
        // thread calls this -- and registerInputDeviceListener/InputDeviceListenerDelegate throws
        // "Can't create handler inside thread ... that has not called Looper.prepare()" the moment
        // that thread has no Looper. This is called from StandardTier.start(), reached through
        // PipelineCoordinator's actor coroutine on the ApplicationScope (Dispatchers.Default) pool
        // -- a plain worker thread with no Looper -- so a null Handler would crash every single
        // time the Standard tier (the universal fallback every other tier downgrades to) starts.
        // Passing an explicit main-thread Handler makes this safe regardless of the caller's thread.
        inputManager.registerInputDeviceListener(listener, mainHandler)
        inputManager.inputDeviceIds.forEach(::handle)
    }

    fun stop() {
        inputManager.unregisterInputDeviceListener(listener)
        // Once unregistered we won't hear about any further unplug, so leaving stale entries
        // marked "connected" would be exactly the same staleness bug onInputDeviceRemoved above
        // now fixes -- clearConnected() exists for precisely this moment.
        deviceIdToStableId.clear()
        deviceRepository.clearConnected()
    }

    private fun handle(deviceId: Int) {
        val device = inputManager.getInputDevice(deviceId) ?: return
        val fingerprint = fingerprintOf(device) ?: return
        deviceIdToStableId[deviceId] = fingerprint.stableId
        scope.launch { deviceRepository.onDeviceConnected(fingerprint) }
    }

    /** Null for virtual/non-peripheral devices (touchscreen, built-in buttons) we don't track. */
    fun fingerprintOf(device: InputDevice): DeviceFingerprint? {
        val sources = device.sources
        val isKeyboard = sources and InputDevice.SOURCE_KEYBOARD == InputDevice.SOURCE_KEYBOARD &&
            device.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC
        val isMouse = sources and InputDevice.SOURCE_MOUSE == InputDevice.SOURCE_MOUSE ||
            sources and InputDevice.SOURCE_MOUSE_RELATIVE == InputDevice.SOURCE_MOUSE_RELATIVE
        if (!isKeyboard && !isMouse) return null
        if (device.isVirtual) return null

        val deviceClass = when {
            isKeyboard && isMouse -> DeviceClass.HYBRID
            isKeyboard -> DeviceClass.KEYBOARD
            else -> DeviceClass.MOUSE
        }
        return DeviceFingerprint(
            stableId = stableId(device.vendorId, device.productId, device.name),
            vendorId = device.vendorId,
            productId = device.productId,
            deviceName = device.name ?: "Unknown device",
            deviceClass = deviceClass
        )
    }

    companion object {
        /**
         * Hash of vendor:product:name. Two identical peripheral models collide on purpose (see
         * Architecture §4.6 KDoc on [DeviceFingerprint]) — most HID devices expose no per-unit
         * serial, so this is the best stable identity available without root.
         */
        fun stableId(vendorId: Int, productId: Int, name: String): String {
            val raw = "$vendorId:$productId:$name"
            val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }.take(16)
        }
    }
}
