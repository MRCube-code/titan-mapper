package com.titanmapper.service.input

import android.content.Context
import android.hardware.input.InputManager
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

    private val listener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = handle(deviceId)
        override fun onInputDeviceChanged(deviceId: Int) = handle(deviceId)
        override fun onInputDeviceRemoved(deviceId: Int) = Unit // id is now invalid; nothing to look up
    }

    fun start() {
        inputManager.registerInputDeviceListener(listener, null)
        inputManager.inputDeviceIds.forEach(::handle)
    }

    fun stop() {
        inputManager.unregisterInputDeviceListener(listener)
    }

    private fun handle(deviceId: Int) {
        val device = inputManager.getInputDevice(deviceId) ?: return
        val fingerprint = fingerprintOf(device) ?: return
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
