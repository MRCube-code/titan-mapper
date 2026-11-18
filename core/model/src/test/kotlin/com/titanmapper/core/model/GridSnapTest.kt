package com.titanmapper.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GridSnapTest {

    @Test
    fun `snap rounds to nearest cell boundary`() {
        // With 4 cells: boundaries at 0.0, 0.25, 0.5, 0.75, 1.0
        assertEquals(0.25f, GridSnap.snap(0.3f, 4), 0.001f)
        assertEquals(0.5f, GridSnap.snap(0.49f, 4), 0.001f)
        assertEquals(0.75f, GridSnap.snap(0.74f, 4), 0.001f)
    }

    @Test
    fun `snap clamps to 0-1`() {
        val result = GridSnap.snap(1.1f, 10)
        assertTrue(result in 0f..1f)
    }

    @Test
    fun `snapControl returns a clamped, valid control`() {
        val control = OverlayControl(
            id = "c1", type = OverlayControlType.BUTTON, label = "X",
            centerX = 0.1f, centerY = 0.9f, width = 0.08f, height = 0.08f
        )
        val snapped = GridSnap.snapControl(control, 24)
        assertTrue(snapped.width >= OverlayControl.MIN_SIZE)
        assertTrue(snapped.height >= OverlayControl.MIN_SIZE)
        assertTrue(snapped.centerX in snapped.width / 2f..(1f - snapped.width / 2f))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `snap throws on zero cells`() {
        GridSnap.snap(0.5f, 0)
    }

    @Test
    fun `OverlayControl clamped keeps alpha in range`() {
        val control = OverlayControl(
            id = "c2", type = OverlayControlType.BUTTON, label = "Y",
            centerX = 0.5f, centerY = 0.5f, alpha = -0.5f
        )
        val clamped = control.clamped()
        assertTrue(clamped.alpha >= OverlayControl.MIN_ALPHA)
    }

    @Test
    fun `OverlayControl clamped constrains center so control stays in canvas`() {
        val control = OverlayControl(
            id = "c3", type = OverlayControlType.BUTTON, label = "Z",
            centerX = 0f, centerY = 0f, width = 0.2f, height = 0.2f
        )
        val clamped = control.clamped()
        // center must be at least half-width from edge
        assertTrue(clamped.centerX >= clamped.width / 2f)
        assertTrue(clamped.centerY >= clamped.height / 2f)
    }
}
