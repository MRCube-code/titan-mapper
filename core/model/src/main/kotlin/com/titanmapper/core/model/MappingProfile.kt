package com.titanmapper.core.model

import kotlinx.serialization.Serializable

/**
 * In-memory / transport representation of a profile — the domain model that
 * `:core:data` repositories map Room's `ProfileEntity`+`BindingEntity` rows into, and that
 * `.titanprofile` export files serialize directly (Architecture §6.1, §6.3).
 *
 * Kept separate from the Room entity intentionally: Room entities are storage-shaped (foreign
 * keys, JSON columns), while [MappingProfile] is engine-shaped (a resolved list of
 * [Binding] ready for O(1) lookup-table construction). `:core:data`'s `ProfileRepository` is
 * the only place that translates between the two.
 */
@Serializable
data class MappingProfile(
    val id: String,
    val name: String,
    /** Null = applies globally, regardless of foreground app. */
    val targetPackageName: String? = null,
    /** Null = applies to every connected device of a matching class. */
    val deviceFingerprintId: String? = null,
    val bindings: List<Binding> = emptyList(),
    val sensitivity: SensitivityPreset = SensitivityPreset.DEFAULT,
    val isActive: Boolean = false,
    /** Higher priority wins when multiple profiles could match the same (device, app) pair. */
    val priority: Int = 0,
    val colorTag: ProfileColor = ProfileColor.BLUE,
    val permissionTier: CaptureTier = CaptureTier.STANDARD,
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION
) {
    companion object {
        /** Bumped whenever the serialized shape changes in a way old clients can't read; checked on import. */
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

@Serializable
data class Binding(
    val id: String,
    val trigger: InputTrigger,
    val action: MappedAction,
    val mode: ActionMode = ActionMode.TAP,
    /** Only consulted when [mode] == REPEAT. */
    val repeatIntervalMillis: Long = 50L,
    val isEnabled: Boolean = true
)

@Serializable
enum class ProfileColor { BLUE, GREEN, RED, PURPLE, ORANGE, TEAL, PINK, GRAY }

/**
 * Mouse sensitivity/acceleration configuration (Architecture §4 "Mouse Engine").
 * [curve] drives how raw deltas are transformed before being handed to the dispatch adapter.
 */
@Serializable
data class SensitivityPreset(
    val xScale: Float = 1.0f,
    val yScale: Float = 1.0f,
    val curve: SensitivityCurve = SensitivityCurve.Linear,
    val accelerationExponent: Float = 1.0f, // 1.0 = no acceleration
    val rawInputEnabled: Boolean = false,
    val mode: PointerMode = PointerMode.DESKTOP,
    val dpiReference: Int = 800
) {
    companion object {
        val DEFAULT = SensitivityPreset()
    }
}

@Serializable
sealed interface SensitivityCurve {
    @Serializable
    data object Linear : SensitivityCurve

    @Serializable
    data object Exponential : SensitivityCurve

    /** User-authored cubic-bezier control points for fully custom response curves. */
    @Serializable
    data class CustomBezier(val p1x: Float, val p1y: Float, val p2x: Float, val p2y: Float) : SensitivityCurve
}

@Serializable
enum class PointerMode {
    /** Smaller, precise movements — good for cursor-driven productivity UIs. */
    PRECISION,

    /** Balanced defaults matching typical desktop OS pointer behavior. */
    DESKTOP,

    /** High raw responsiveness, acceleration typically disabled, tuned for FPS/aim-critical play. */
    GAMING
}
