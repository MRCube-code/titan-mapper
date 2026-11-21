package com.titanmapper.service.tier

import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import com.titanmapper.core.model.CaptureTier
import com.titanmapper.core.model.MappedAction
import com.titanmapper.service.engine.DispatchPhase
import com.titanmapper.service.engine.OutputDispatchSink
import com.titanmapper.service.input.AndroidKeyCodeTranslator
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Method
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shizuku tier (Architecture §4.4). Obtains a Binder to the system `InputManager` service via
 * Shizuku's privileged process (no root, no persistent ADB connection needed after the user
 * activates Shizuku once) and calls the hidden `InputManager.injectInputEvent(InputEvent, int)`
 * through reflection, exempted from the hidden-API greylist by [HiddenApiBypass].
 *
 * This tier only provides DISPATCH (system-wide injection). It still relies on
 * [com.titanmapper.service.input.ActivityInputBridgeImpl] or the Accessibility capture path for
 * *capture*, since Shizuku's granted permission (`INJECT_EVENTS`-equivalent) does not, on its
 * own, give this app a system-wide key-event listener — documented honestly rather than implying
 * Shizuku unlocks capture it does not.
 */
@Singleton
class ShizukuTier @Inject constructor(
    @ApplicationContext private val context: Context
) : TierBackend {

    override val tier = CaptureTier.SHIZUKU

    @Volatile private var inputManagerBinder: android.os.IInterface? = null
    @Volatile private var injectMethod: Method? = null

    override val sink = object : OutputDispatchSink {
        override val tier = CaptureTier.SHIZUKU

        override fun dispatch(action: MappedAction, phase: DispatchPhase): Boolean = try {
            when (action) {
                is MappedAction.KeyInjection -> injectKey(AndroidKeyCodeTranslator.toAndroid(action.key), phase)
                is MappedAction.MouseInjection.Move -> injectMouseMove(action.deltaX, action.deltaY)
                is MappedAction.MouseInjection.ButtonPress -> injectMouseButton(action.button, phase)
                is MappedAction.SystemAction -> injectSystemAction(action.type)
                is MappedAction.TouchTap, is MappedAction.TouchDrag, is MappedAction.MacroRun -> true
                else -> false
            }
        } catch (t: Throwable) {
            false
        }

        override fun supports(action: MappedAction): Boolean = when (action) {
            is MappedAction.KeyInjection, is MappedAction.MouseInjection,
            is MappedAction.SystemAction, is MappedAction.TouchTap, is MappedAction.TouchDrag,
            is MappedAction.MacroRun -> true
            else -> false
        }

        private fun injectKey(androidKeyCode: Int?, phase: DispatchPhase): Boolean {
            if (androidKeyCode == null) return false
            val method = injectMethod ?: return false
            val binder = inputManagerBinder ?: return false
            val now = android.os.SystemClock.uptimeMillis()
            val action = if (phase == DispatchPhase.UP) KeyEvent.ACTION_UP else KeyEvent.ACTION_DOWN
            val event = KeyEvent(now, now, action, androidKeyCode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
            return invokeInject(method, binder, event)
        }

        private fun injectMouseMove(dx: Float, dy: Float): Boolean {
            val method = injectMethod ?: return false
            val binder = inputManagerBinder ?: return false
            val now = android.os.SystemClock.uptimeMillis()
            val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE })
            val coords = arrayOf(MotionEvent.PointerCoords().apply { x = dx; y = dy })
            val event = MotionEvent.obtain(
                now, now, MotionEvent.ACTION_HOVER_MOVE, 1, props, coords, 0, 0, 1f, 1f, 0, 0,
                InputDevice.SOURCE_MOUSE_RELATIVE, 0
            )
            return try { invokeInject(method, binder, event) } finally { event.recycle() }
        }

        /**
         * Injects a button-state-change MotionEvent. Real HID mice report buttons alongside
         * relative-motion reports, so this synthesizes a zero-delta ACTION_BUTTON_PRESS/RELEASE
         * at the last known pointer location (0,0 in relative-mouse space, since this tier never
         * tracks absolute position) — sufficient for "click" semantics in apps that only check
         * buttonState, honestly insufficient for apps that require a real absolute coordinate.
         */
        private fun injectMouseButton(button: com.titanmapper.core.model.MouseButton, phase: DispatchPhase): Boolean {
            val method = injectMethod ?: return false
            val binder = inputManagerBinder ?: return false
            val now = android.os.SystemClock.uptimeMillis()
            val buttonFlag = when (button) {
                com.titanmapper.core.model.MouseButton.LEFT -> MotionEvent.BUTTON_PRIMARY
                com.titanmapper.core.model.MouseButton.RIGHT -> MotionEvent.BUTTON_SECONDARY
                com.titanmapper.core.model.MouseButton.MIDDLE -> MotionEvent.BUTTON_TERTIARY
                com.titanmapper.core.model.MouseButton.BACK -> MotionEvent.BUTTON_BACK
                com.titanmapper.core.model.MouseButton.FORWARD -> MotionEvent.BUTTON_FORWARD
                com.titanmapper.core.model.MouseButton.EXTRA_1 -> MotionEvent.BUTTON_BACK
                com.titanmapper.core.model.MouseButton.EXTRA_2 -> MotionEvent.BUTTON_FORWARD
            }
            val action = if (phase == DispatchPhase.UP) MotionEvent.ACTION_BUTTON_RELEASE else MotionEvent.ACTION_BUTTON_PRESS
            val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE })
            val coords = arrayOf(MotionEvent.PointerCoords().apply { x = 0f; y = 0f })
            // Note: MotionEvent.actionButton (which identifies WHICH button changed) has no public
            // setter; only the hidden InputManager path on the Root tier can set it precisely via
            // a raw evdev/uinput report. Here buttonState alone carries the button semantics,
            // which most apps honor for click handling even without actionButton set.
            val event = MotionEvent.obtain(
                now, now, action, 1, props, coords, 0,
                if (phase == DispatchPhase.UP) 0 else buttonFlag, 1f, 1f, 0, 0,
                InputDevice.SOURCE_MOUSE_RELATIVE, 0
            )
            return try { invokeInject(method, binder, event) } finally { event.recycle() }
        }

        private fun injectSystemAction(type: com.titanmapper.core.model.SystemActionType): Boolean {
            val code = when (type) {
                com.titanmapper.core.model.SystemActionType.BACK -> KeyEvent.KEYCODE_BACK
                com.titanmapper.core.model.SystemActionType.HOME -> KeyEvent.KEYCODE_HOME
                com.titanmapper.core.model.SystemActionType.RECENTS -> KeyEvent.KEYCODE_APP_SWITCH
                else -> return false // notifications/quick-settings/screenshot/lock have no reliable KeyEvent equivalent via this path
            }
            injectKey(code, DispatchPhase.DOWN)
            return injectKey(code, DispatchPhase.UP)
        }

        private fun invokeInject(method: Method, binder: android.os.IInterface, event: android.view.InputEvent): Boolean =
            method.invoke(binder, event, INJECT_INPUT_EVENT_MODE_ASYNC) as? Boolean ?: false
    }

    override suspend fun start(): Boolean {
        if (!Shizuku.pingBinder()) return false
        if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return suspendRequestPermission()
        }
        return bindInputManager()
    }

    override fun stop() {
        inputManagerBinder = null
        injectMethod = null
    }

    override fun isHealthy(): Boolean = Shizuku.pingBinder() &&
        Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED &&
        inputManagerBinder != null

    private suspend fun suspendRequestPermission(): Boolean = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        val code = (0..Int.MAX_VALUE).random()
        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode != code) return
                Shizuku.removeRequestPermissionResultListener(this)
                if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    cont.resumeWith(Result.success(bindInputManager()))
                } else {
                    cont.resumeWith(Result.success(false))
                }
            }
        }
        Shizuku.addRequestPermissionResultListener(listener)
        Shizuku.requestPermission(code)
        cont.invokeOnCancellation { Shizuku.removeRequestPermissionResultListener(listener) }
    }

    private fun bindInputManager(): Boolean = try {
        HiddenApiBypass.addHiddenApiExemptions("Landroid/hardware/input/IInputManager;", "Landroid/hardware/input/InputManager;")
        val raw = SystemServiceHelper.getSystemService("input")
        val wrapped = ShizukuBinderWrapper(raw)
        val stubClass = Class.forName("android.hardware.input.IInputManager\$Stub")
        val asInterface = stubClass.getMethod("asInterface", android.os.IBinder::class.java)
        val iface = asInterface.invoke(null, wrapped) as android.os.IInterface
        val method = iface.javaClass.getMethod("injectInputEvent", android.view.InputEvent::class.java, Int::class.javaPrimitiveType)
        inputManagerBinder = iface
        injectMethod = method
        true
    } catch (t: Throwable) {
        false
    }

    private companion object {
        /** android.hardware.input.InputManager.INJECT_INPUT_EVENT_MODE_ASYNC (hidden constant, stable value since API 1). */
        const val INJECT_INPUT_EVENT_MODE_ASYNC = 0
    }
}
