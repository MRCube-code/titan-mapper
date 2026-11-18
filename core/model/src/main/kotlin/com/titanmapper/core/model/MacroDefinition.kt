package com.titanmapper.core.model

import kotlinx.serialization.Serializable

/**
 * A macro: an ordered list of [MacroStep]s, optionally wrapped in loops/conditionals. Executed
 * by `:service`'s `MacroExecutor` on a dedicated coroutine per concurrently-running macro slot
 * (Architecture §10). Deliberately data-only (no behavior) so it can be imported/exported as
 * JSON and edited visually in the macro editor UI without touching execution code.
 */
@Serializable
data class MacroDefinition(
    val id: String,
    val name: String,
    val steps: List<MacroStep>,
    /** True = available from every profile; false = scoped to the profile that recorded it. */
    val isGlobal: Boolean = false,
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

@Serializable
sealed interface MacroStep {
    @Serializable
    data class PressKey(val key: KeyCode) : MacroStep

    @Serializable
    data class ReleaseKey(val key: KeyCode) : MacroStep

    @Serializable
    data class ClickMouse(val button: MouseButton) : MacroStep

    @Serializable
    data class MoveMouse(val deltaX: Float, val deltaY: Float) : MacroStep

    @Serializable
    data class Delay(val millis: Long) : MacroStep

    /** Marks the start of a repeated block; paired with the next [LoopEnd] at the same nesting level. */
    @Serializable
    data class LoopStart(val count: Int?) : MacroStep // null count = infinite, runs until macro is cancelled

    @Serializable
    data object LoopEnd : MacroStep

    /**
     * Branches execution. [MacroCondition] is evaluated at runtime by the executor against live
     * engine/device state — intentionally not a scripting expression (Architecture §15.5: no
     * embedded interpreter in v1, to avoid widening the security surface in §12).
     */
    @Serializable
    data class ConditionalIf(
        val condition: MacroCondition,
        val thenSteps: List<MacroStep>,
        val elseSteps: List<MacroStep> = emptyList()
    ) : MacroStep
}

@Serializable
sealed interface MacroCondition {
    @Serializable
    data class ForegroundAppIs(val packageName: String) : MacroCondition

    @Serializable
    data class ToggleIsActive(val bindingId: String) : MacroCondition

    @Serializable
    data class DeviceIsConnected(val deviceFingerprintId: String) : MacroCondition

    @Serializable
    data class Not(val condition: MacroCondition) : MacroCondition
}
