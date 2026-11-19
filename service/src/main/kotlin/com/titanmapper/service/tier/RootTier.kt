package com.titanmapper.service.tier

import com.titanmapper.core.model.CaptureTier
import com.titanmapper.core.model.DeviceClass
import com.titanmapper.core.model.DeviceFingerprint
import com.titanmapper.core.model.KeyAction
import com.titanmapper.core.model.KeyCode
import com.titanmapper.core.model.MappedAction
import com.titanmapper.core.model.MouseButton
import com.titanmapper.core.model.RawInputEvent
import com.titanmapper.service.engine.DispatchPhase
import com.titanmapper.service.engine.OutputDispatchSink
import com.titanmapper.service.input.InputEventBus
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.DataInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Root tier (Architecture §4.5).
 *
 * CAPTURE: streams raw Linux evdev `input_event` structs (the kernel's native format, 24 bytes
 * on 64-bit: `struct timeval` (16) + `__u16 type` + `__u16 code` + `__s32 value`) from every
 * `/dev/input/eventN` that looks like a keyboard/mouse in `/proc/bus/input/devices`, through a
 * long-lived root shell (`libsu`) piping `cat` into this process's stdin. This is **non-exclusive**
 * in this build: we deliberately do NOT issue `ioctl(fd, EVIOCGRAB, 1)` here, because that ioctl
 * needs a native fd held open in-process (JNI), not a byte stream read through `cat` over a
 * shell pipe. The practical effect, disclosed to the user: the OS *also* sees these events
 * (possible double-input if a profile both remaps and lets the raw key reach the foreground
 * app). A native helper that opens the evdev fd directly and grabs it is the documented future
 * upgrade (see ARCHITECTURE.md §15.4 and the README roadmap).
 *
 * DISPATCH: the `uinput` virtual-HID path described in the architecture doc needs the same
 * native helper. Until that lands, this build dispatches through the `input` shell command
 * (`input keyevent`, `input tap`, `input swipe`, `input motionevent`) run via libsu — fully
 * system-wide and needing no Activity focus, but coarser than a true HID report (no raw scan
 * codes, limited relative-mouse semantics) and measurably higher latency than a native uinput
 * write, since every action spawns a shell command.
 */
@Singleton
class RootTier @Inject constructor(
    private val bus: InputEventBus
) : TierBackend {

    override val tier = CaptureTier.ROOT

    private var captureJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    @Volatile private var granted = false

    override val sink = object : OutputDispatchSink {
        override val tier = CaptureTier.ROOT

        override fun dispatch(action: MappedAction, phase: DispatchPhase): Boolean {
            val cmd = when (action) {
                is MappedAction.KeyInjection ->
                    if (phase == DispatchPhase.UP) null // `input keyevent` is a full press+release; nothing to send on UP
                    else androidKeyEventName(action.key)?.let { "input keyevent $it" }
                is MappedAction.TouchTap ->
                    if (phase != DispatchPhase.UP) "input tap ${px(action.normalizedX, true)} ${px(action.normalizedY, false)}" else null
                is MappedAction.TouchDrag -> "input swipe ${px(action.fromX, true)} ${px(action.fromY, false)} " +
                    "${px(action.toX, true)} ${px(action.toY, false)} ${action.durationMillis}"
                is MappedAction.SystemAction -> systemActionCommand(action.type)
                is MappedAction.MouseInjection -> null // needs the native uinput helper; honestly unsupported for now
                is MappedAction.MacroRun -> return true
                else -> null
            } ?: return false
            return Shell.cmd(cmd).exec().isSuccess
        }

        override fun supports(action: MappedAction): Boolean = when (action) {
            is MappedAction.KeyInjection, is MappedAction.TouchTap, is MappedAction.TouchDrag,
            is MappedAction.SystemAction, is MappedAction.MacroRun -> true
            is MappedAction.MouseInjection -> false
            else -> false
        }

        private fun px(normalized: Float, isX: Boolean): Int {
            val metrics = android.content.res.Resources.getSystem().displayMetrics
            return (normalized * if (isX) metrics.widthPixels else metrics.heightPixels).toInt()
        }

        private fun systemActionCommand(type: com.titanmapper.core.model.SystemActionType): String? = when (type) {
            com.titanmapper.core.model.SystemActionType.BACK -> "input keyevent KEYCODE_BACK"
            com.titanmapper.core.model.SystemActionType.HOME -> "input keyevent KEYCODE_HOME"
            com.titanmapper.core.model.SystemActionType.RECENTS -> "input keyevent KEYCODE_APP_SWITCH"
            com.titanmapper.core.model.SystemActionType.NOTIFICATIONS -> "cmd statusbar expand-notifications"
            com.titanmapper.core.model.SystemActionType.QUICK_SETTINGS -> "cmd statusbar expand-settings"
            com.titanmapper.core.model.SystemActionType.SCREENSHOT -> "input keyevent KEYCODE_SYSRQ"
            com.titanmapper.core.model.SystemActionType.LOCK_SCREEN -> "input keyevent KEYCODE_POWER"
        }
    }

    override suspend fun start(): Boolean = withContext(Dispatchers.IO) {
        granted = Shell.getShell().isRoot
        if (!granted) return@withContext false
        captureJob?.cancel()
        captureJob = scope.launch { captureLoop() }
        true
    }

    override fun stop() {
        captureJob?.cancel()
        captureJob = null
    }

    override fun isHealthy(): Boolean = granted && Shell.getCachedShell()?.isRoot == true

    /**
     * One coroutine per tracked device node, each piping `cat /dev/input/eventN` through a root
     * shell and parsing 24-byte evdev records. `EV_KEY` (type 1) reports are translated to
     * [RawInputEvent.Key]/[RawInputEvent.Button]; `EV_REL` (type 2) reports accumulate into
     * [RawInputEvent.Motion]/[RawInputEvent.Scroll].
     */
    private suspend fun captureLoop() = withContext(Dispatchers.IO) {
        val devices = listEvdevDevices()
        devices.map { node -> launch { streamDevice(node) } }.forEach { it.join() }
    }

    private fun listEvdevDevices(): List<EvdevNode> {
        val out = Shell.cmd("cat /proc/bus/input/devices").exec().out.joinToString("\n")
        return EvdevDeviceListParser.parse(out)
    }

    /**
     * Streams one evdev node's binary `input_event` records. Deliberately uses a plain
     * `ProcessBuilder("su", "-c", "cat path")` rather than libsu's text-oriented `Shell.cmd`
     * helpers, because the latter buffer/line-split stdout — fatal for a binary protocol where
     * `\n` (0x0A) is a perfectly valid byte inside a timestamp or value field. The child
     * process's raw stdout is read directly as a [DataInputStream].
     */
    private suspend fun streamDevice(node: EvdevNode) {
        val fingerprint = DeviceFingerprint(
            stableId = com.titanmapper.service.input.InputDeviceMonitor.stableId(node.vendor, node.product, node.name),
            vendorId = node.vendor,
            productId = node.product,
            deviceName = node.name,
            deviceClass = node.deviceClass
        )
        bus.publish(RawInputEvent.DeviceConnectionChanged(fingerprint, System.nanoTime(), CaptureTier.ROOT, connected = true))
        var process: Process? = null
        try {
            process = ProcessBuilder("su", "-c", "cat '${node.path}'").redirectErrorStream(false).start()
            val input = DataInputStream(BufferedInputStream(process.inputStream, EVENT_SIZE * 64))
            val record = ByteArray(EVENT_SIZE)
            var relX = 0f
            var relY = 0f
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                input.readFully(record)
                val type = readU16LE(record, TYPE_OFFSET)
                val code = readU16LE(record, CODE_OFFSET)
                val value = readS32LE(record, VALUE_OFFSET)
                when (type) {
                    EV_KEY -> handleKey(fingerprint, node, code, value)
                    EV_REL -> when (code) {
                        REL_X -> relX += value
                        REL_Y -> relY += value
                        REL_WHEEL -> bus.publish(
                            RawInputEvent.Scroll(fingerprint, System.nanoTime(), CaptureTier.ROOT,
                                if (value > 0) com.titanmapper.core.model.ScrollDirection.UP else com.titanmapper.core.model.ScrollDirection.DOWN,
                                kotlin.math.abs(value).toFloat())
                        )
                    }
                    EV_SYN -> if (relX != 0f || relY != 0f) {
                        bus.publish(RawInputEvent.Motion(fingerprint, System.nanoTime(), CaptureTier.ROOT, relX, relY))
                        relX = 0f; relY = 0f
                    }
                }
            }
        } catch (_: Exception) {
            // A single unreadable/disconnected device must not take down capture for the others.
        } finally {
            process?.destroy()
            bus.publish(RawInputEvent.DeviceConnectionChanged(fingerprint, System.nanoTime(), CaptureTier.ROOT, connected = false))
        }
    }

    private fun handleKey(fingerprint: DeviceFingerprint, node: EvdevNode, evdevCode: Int, value: Int) {
        if (value == 2) return // key-repeat record; the engine owns repeat semantics, not the kernel
        val action = if (value == 1) KeyAction.DOWN else KeyAction.UP
        val mouseButton = EVDEV_BUTTON_CODES[evdevCode]
        if (mouseButton != null) {
            bus.publish(RawInputEvent.Button(fingerprint, System.nanoTime(), CaptureTier.ROOT, mouseButton, action))
            return
        }
        val keyCode = EVDEV_TO_KEYCODE[evdevCode] ?: return
        bus.publish(RawInputEvent.Key(fingerprint, System.nanoTime(), CaptureTier.ROOT, keyCode, action))
    }

    private fun readU16LE(b: ByteArray, offset: Int): Int =
        (b[offset].toInt() and 0xFF) or ((b[offset + 1].toInt() and 0xFF) shl 8)

    private fun readS32LE(b: ByteArray, offset: Int): Int =
        (b[offset].toInt() and 0xFF) or ((b[offset + 1].toInt() and 0xFF) shl 8) or
            ((b[offset + 2].toInt() and 0xFF) shl 16) or ((b[offset + 3].toInt() and 0xFF) shl 24)

    private object EvdevDeviceListParser {
        fun parse(procOutput: String): List<EvdevNode> {
            val nodes = mutableListOf<EvdevNode>()
            var name = ""; var vendor = 0; var product = 0; var handlers = ""
            for (line in procOutput.lines()) {
                when {
                    line.startsWith("N: Name=") -> name = line.substringAfter("=").trim('"')
                    line.startsWith("I: ") -> {
                        Regex("Vendor=([0-9a-fA-F]+)").find(line)?.let { vendor = it.groupValues[1].toInt(16) }
                        Regex("Product=([0-9a-fA-F]+)").find(line)?.let { product = it.groupValues[1].toInt(16) }
                    }
                    line.startsWith("H: Handlers=") -> handlers = line.substringAfter("=")
                    line.isBlank() -> {
                        val eventNode = Regex("event(\\d+)").find(handlers)?.value
                        if (eventNode != null && name.isNotBlank()) {
                            val isKeyboard = handlers.contains("kbd")
                            val isMouse = handlers.contains("mouse")
                            if (isKeyboard || isMouse) {
                                nodes += EvdevNode(
                                    path = "/dev/input/$eventNode",
                                    name = name,
                                    vendor = vendor,
                                    product = product,
                                    deviceClass = if (isKeyboard && isMouse) DeviceClass.HYBRID
                                    else if (isKeyboard) DeviceClass.KEYBOARD else DeviceClass.MOUSE
                                )
                            }
                        }
                        name = ""; vendor = 0; product = 0; handlers = ""
                    }
                }
            }
            return nodes
        }
    }

    private data class EvdevNode(val path: String, val name: String, val vendor: Int, val product: Int, val deviceClass: DeviceClass)

    /** `input keyevent` accepts the full symbolic name (e.g. "KEYCODE_A"), not a bare suffix. */
    private fun androidKeyEventName(key: KeyCode): String? =
        com.titanmapper.service.input.AndroidKeyCodeTranslator.toAndroid(key)?.let { android.view.KeyEvent.keyCodeToString(it) }

    private companion object {
        const val EVENT_SIZE = 24 // struct input_event on 64-bit: 16 (timeval) + 2 + 2 + 4
        const val TYPE_OFFSET = 16
        const val CODE_OFFSET = 18
        const val VALUE_OFFSET = 20

        const val EV_SYN = 0x00
        const val EV_KEY = 0x01
        const val EV_REL = 0x02
        const val REL_X = 0x00
        const val REL_Y = 0x01
        const val REL_WHEEL = 0x08

        // Standard Linux input-event-codes.h values for mouse buttons (BTN_LEFT..BTN_TASK range).
        val EVDEV_BUTTON_CODES: Map<Int, MouseButton> = mapOf(
            0x110 to MouseButton.LEFT,   // BTN_LEFT
            0x111 to MouseButton.RIGHT,  // BTN_RIGHT
            0x112 to MouseButton.MIDDLE, // BTN_MIDDLE
            0x113 to MouseButton.BACK,   // BTN_SIDE
            0x114 to MouseButton.FORWARD // BTN_EXTRA
        )

        /**
         * Evdev `KEY_*` codes (input-event-codes.h) that differ in numbering from Android's
         * KeyEvent constants, mapped straight to [KeyCode]. Only a practical subset is listed;
         * [EVDEV_BUTTON_CODES] above intercepts mouse buttons before this map is consulted.
         */
        val EVDEV_TO_KEYCODE: Map<Int, KeyCode> = mapOf(
            1 to KeyCode.ESCAPE, 2 to KeyCode.DIGIT_1, 3 to KeyCode.DIGIT_2, 4 to KeyCode.DIGIT_3,
            5 to KeyCode.DIGIT_4, 6 to KeyCode.DIGIT_5, 7 to KeyCode.DIGIT_6, 8 to KeyCode.DIGIT_7,
            9 to KeyCode.DIGIT_8, 10 to KeyCode.DIGIT_9, 11 to KeyCode.DIGIT_0,
            14 to KeyCode.BACKSPACE, 15 to KeyCode.TAB, 16 to KeyCode.Q, 17 to KeyCode.W,
            18 to KeyCode.E, 19 to KeyCode.R, 20 to KeyCode.T, 21 to KeyCode.Y, 22 to KeyCode.U,
            23 to KeyCode.I, 24 to KeyCode.O, 25 to KeyCode.P, 28 to KeyCode.ENTER,
            29 to KeyCode.CTRL_LEFT, 30 to KeyCode.A, 31 to KeyCode.S, 32 to KeyCode.D,
            33 to KeyCode.F, 34 to KeyCode.G, 35 to KeyCode.H, 36 to KeyCode.J, 37 to KeyCode.K,
            38 to KeyCode.L, 42 to KeyCode.SHIFT_LEFT, 44 to KeyCode.Z, 45 to KeyCode.X,
            46 to KeyCode.C, 47 to KeyCode.V, 48 to KeyCode.B, 49 to KeyCode.N, 50 to KeyCode.M,
            54 to KeyCode.SHIFT_RIGHT, 56 to KeyCode.ALT_LEFT, 57 to KeyCode.SPACE,
            58 to KeyCode.CAPS_LOCK, 97 to KeyCode.CTRL_RIGHT, 100 to KeyCode.ALT_RIGHT,
            103 to KeyCode.ARROW_UP, 105 to KeyCode.ARROW_LEFT, 106 to KeyCode.ARROW_RIGHT,
            108 to KeyCode.ARROW_DOWN
        )
    }
}
