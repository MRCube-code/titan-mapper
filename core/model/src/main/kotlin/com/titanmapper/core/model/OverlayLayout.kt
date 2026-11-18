package com.titanmapper.core.model

import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

@Serializable
enum class ScreenOrientation { PORTRAIT, LANDSCAPE }

@Serializable
enum class OverlayControlType { BUTTON, DPAD, JOYSTICK, AIM_ZONE, MACRO_BUTTON }

/**
 * One draggable/resizable control on the overlay. All geometry is normalized to [0,1] of the
 * overlay canvas so a layout survives resolution, density and window-size changes; it is
 * resolved to pixels only at render/dispatch time. Portrait and landscape layouts are stored
 * separately ([OverlayLayout.orientation]) because the same normalized spot rarely makes sense in both.
 */
@Serializable
data class OverlayControl(
    val id: String,
    val type: OverlayControlType,
    val label: String,
    val centerX: Float,
    val centerY: Float,
    val width: Float = 0.1f,
    val height: Float = 0.1f,
    /** 0.1..1.0; floor keeps a control from becoming invisible and un-editable. */
    val alpha: Float = 0.6f,
    /** Physical input that drives this control, e.g. key W driving a joystick direction. */
    val trigger: InputTrigger? = null,
    /** What the control does on activation; null = purely visual. */
    val action: MappedAction? = null
) {
    fun clamped(): OverlayControl {
        val w = width.coerceIn(MIN_SIZE, 1f)
        val h = height.coerceIn(MIN_SIZE, 1f)
        return copy(
            width = w,
            height = h,
            centerX = centerX.coerceIn(w / 2f, 1f - w / 2f),
            centerY = centerY.coerceIn(h / 2f, 1f - h / 2f),
            alpha = alpha.coerceIn(MIN_ALPHA, 1f)
        )
    }

    companion object {
        const val MIN_SIZE = 0.03f
        const val MIN_ALPHA = 0.1f
    }
}

@Serializable
data class OverlayLayout(
    val profileId: String,
    val orientation: ScreenOrientation,
    val controls: List<OverlayControl> = emptyList(),
    val gridColumns: Int = 24,
    val snapToGrid: Boolean = true
) {
    companion object {
        fun empty(profileId: String, orientation: ScreenOrientation) = OverlayLayout(profileId, orientation)
    }
}

/** Pure snap-to-grid math, unit-testable without Compose. */
object GridSnap {
    /** Snaps a normalized coordinate to the nearest of [cells] equal divisions. */
    fun snap(value: Float, cells: Int): Float {
        require(cells > 0) { "cells must be positive" }
        val step = 1f / cells
        return ((value / step).roundToInt() * step).coerceIn(0f, 1f)
    }

    fun snapControl(control: OverlayControl, cells: Int): OverlayControl =
        control.copy(
            centerX = snap(control.centerX, cells),
            centerY = snap(control.centerY, cells),
            width = snap(control.width, cells).coerceAtLeast(1f / cells),
            height = snap(control.height, cells).coerceAtLeast(1f / cells)
        ).clamped()
}