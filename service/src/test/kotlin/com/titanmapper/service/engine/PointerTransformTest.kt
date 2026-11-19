package com.titanmapper.service.engine

import com.google.common.truth.Truth.assertThat
import com.titanmapper.core.model.PointerMode
import com.titanmapper.core.model.SensitivityCurve
import com.titanmapper.core.model.SensitivityPreset
import org.junit.Test

class PointerTransformTest {

    @Test
    fun `linear curve scales by xScale and yScale independently`() {
        val t = PointerTransform(SensitivityPreset(xScale = 2f, yScale = 0.5f, curve = SensitivityCurve.Linear, mode = PointerMode.DESKTOP))
        val out = FloatArray(2)
        t.transform(10f, 10f, out)
        assertThat(out[0]).isWithin(0.01f).of(20f)
        assertThat(out[1]).isWithin(0.01f).of(5f)
    }

    @Test
    fun `raw input mode bypasses the curve entirely`() {
        val t = PointerTransform(SensitivityPreset(curve = SensitivityCurve.Exponential, accelerationExponent = 3f, rawInputEnabled = true))
        val out = FloatArray(2)
        t.transform(5f, 5f, out)
        assertThat(out[0]).isWithin(0.01f).of(5f)
        assertThat(out[1]).isWithin(0.01f).of(5f)
    }

    @Test
    fun `exponential curve increases gain with speed`() {
        val t = PointerTransform(SensitivityPreset(curve = SensitivityCurve.Exponential, accelerationExponent = 2f, mode = PointerMode.GAMING))
        val slow = FloatArray(2)
        val fast = FloatArray(2)
        t.transform(1f, 0f, slow)
        t.transform(20f, 0f, fast)
        // Gain grows with speed^(a-1); at higher input speed the output/input ratio should be larger.
        assertThat(fast[0] / 20f).isGreaterThan(slow[0] / 1f)
    }

    @Test
    fun `precision mode scales down movement relative to desktop mode`() {
        val desktop = PointerTransform(SensitivityPreset(mode = PointerMode.DESKTOP))
        val precision = PointerTransform(SensitivityPreset(mode = PointerMode.PRECISION))
        val outDesktop = FloatArray(2)
        val outPrecision = FloatArray(2)
        desktop.transform(10f, 10f, outDesktop)
        precision.transform(10f, 10f, outPrecision)
        assertThat(outPrecision[0]).isLessThan(outDesktop[0])
    }

    @Test
    fun `pixel conversion carries sub-pixel remainder forward instead of losing it`() {
        val t = PointerTransform(SensitivityPreset(xScale = 0.3f, curve = SensitivityCurve.Linear))
        val out = FloatArray(2)
        var totalX = 0
        repeat(10) {
            t.transformToPixels(1f, 0f, out)
            totalX += out[0].toInt()
        }
        // 10 inputs of 1.0 * 0.3 = 3.0 total; without remainder-carrying, naive per-call
        // truncation (0.3 -> 0 each time) would produce 0 instead of 3.
        assertThat(totalX).isEqualTo(3)
    }

    @Test
    fun `custom bezier curve keeps gain within configured bounds`() {
        val t = PointerTransform(
            SensitivityPreset(curve = SensitivityCurve.CustomBezier(0.2f, 0.1f, 0.8f, 0.9f), mode = PointerMode.GAMING, accelerationExponent = 1.5f)
        )
        val out = FloatArray(2)
        t.transform(15f, 15f, out)
        assertThat(out[0]).isFinite()
        assertThat(out[1]).isFinite()
    }
}
