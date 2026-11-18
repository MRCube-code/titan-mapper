package com.titanmapper.core.model

/**
 * The single normalized shape every capture tier (Standard / Accessibility / Shizuku / Root)
 * emits into the `InputEventBus`, as described in Architecture §4.6. The MappingEngine only
 * ever consumes [RawInputEvent] — it has no idea which tier produced it.
 *
 * Deliberately a `sealed interface` (not `sealed class`) so implementations can be plain
 * `data class`es without inheriting a shared constructor, keeping each variant lean for the
 * hot capture path. [monotonicNanos] always comes from a monotonic clock
 * (`System.nanoTime()`-equivalent) — never wall-clock — so latency math (Architecture §5) is
 * immune to clock adjustments.
 */
sealed interface RawInputEvent {
    /** Fingerprint of the physical device that produced this event. See [DeviceFingerprint]. */
    val deviceFingerprint: DeviceFingerprint

    /** Monotonic capture timestamp in nanoseconds, used for latency measurement and macro recording. */
    val monotonicNanos: Long

    /** Which permission tier's capture adapter produced this event — surfaced in diagnostics/event logger. */
    val sourceTier: CaptureTier

    data class Key(
        override val deviceFingerprint: DeviceFingerprint,
        override val monotonicNanos: Long,
        override val sourceTier: CaptureTier,
        val keyCode: KeyCode,
        val action: KeyAction
    ) : RawInputEvent

    data class Button(
        override val deviceFingerprint: DeviceFingerprint,
        override val monotonicNanos: Long,
        override val sourceTier: CaptureTier,
        val button: MouseButton,
        val action: KeyAction
    ) : RawInputEvent

    /** Continuous relative pointer movement, pre-sensitivity-curve (raw deltas from the device). */
    data class Motion(
        override val deviceFingerprint: DeviceFingerprint,
        override val monotonicNanos: Long,
        override val sourceTier: CaptureTier,
        val deltaX: Float,
        val deltaY: Float
    ) : RawInputEvent

    data class Scroll(
        override val deviceFingerprint: DeviceFingerprint,
        override val monotonicNanos: Long,
        override val sourceTier: CaptureTier,
        val direction: ScrollDirection,
        /** Fractional ticks for high-resolution scroll wheels; 1.0f = one detent on a standard wheel. */
        val amount: Float
    ) : RawInputEvent

    /** A device connected/disconnected mid-session — the Engine uses this to drop/rebuild profile bindings. */
    data class DeviceConnectionChanged(
        override val deviceFingerprint: DeviceFingerprint,
        override val monotonicNanos: Long,
        override val sourceTier: CaptureTier,
        val connected: Boolean
    ) : RawInputEvent
}

enum class KeyAction { DOWN, UP }

enum class CaptureTier { STANDARD, ACCESSIBILITY, SHIZUKU, ROOT }

/**
 * Identifies one physical keyboard/mouse so a [com.titanmapper.core.model.MappingProfile] can
 * bind to a *specific* device rather than "any keyboard" — required for the multi-device
 * requirement ("two keyboards, two different profiles").
 *
 * [stableId] is a hash of vendorId:productId:deviceName, stable across reconnects and reboots
 * for the same physical unit (two identical mouse models still collide on vendor/product id,
 * which is acceptable — users can disambiguate via [DeviceClass] + nickname in the UI, documented
 * as a known limitation since USB/Bluetooth HID exposes no true per-unit serial on most peripherals).
 */
data class DeviceFingerprint(
    val stableId: String,
    val vendorId: Int,
    val productId: Int,
    val deviceName: String,
    val deviceClass: DeviceClass
)

enum class DeviceClass { KEYBOARD, MOUSE, HYBRID, GAMEPAD, UNKNOWN }
