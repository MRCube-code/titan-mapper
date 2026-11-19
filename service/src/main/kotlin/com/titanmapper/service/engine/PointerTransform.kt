package com.titanmapper.service.engine

import com.titanmapper.core.model.PointerMode
import com.titanmapper.core.model.SensitivityCurve
import com.titanmapper.core.model.SensitivityPreset
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.pow

/**
 * Applies a [SensitivityPreset] to raw relative mouse deltas (Architecture §4 "Mouse Engine").
 *
 * Pipeline per sample:  raw delta -> acceleration/curve gain -> mode multiplier -> per-axis scale.
 *
 * Allocation-free: results are written into the caller-supplied [out] array
 * (out[0] = x, out[1] = y), because this runs once per mouse report (up to 1000+ Hz).
 *
 * Sub-pixel remainders are carried in [remX]/[remY] so slow movement is never lost to rounding
 * when the output tier needs integer deltas (uinput/injectInputEvent rel axes).
 *
 * Not thread-safe: use one instance per pointer-processing coroutine.
 */
class PointerTransform(private var preset: SensitivityPreset) {

    private var remX = 0f
    private var remY = 0f

    fun updatePreset(newPreset: SensitivityPreset) {
        preset = newPreset
        remX = 0f
        remY = 0f
    }

    /** Transforms a raw delta; writes float result to [out]. */
    fun transform(dx: Float, dy: Float, out: FloatArray) {
        val p = preset
        val gain = if (p.rawInputEnabled || p.mode == PointerMode.GAMING && p.accelerationExponent <= 1f) {
            1f // raw mode / gaming-without-accel: bypass the curve entirely
        } else {
            curveGain(hypot(dx, dy), p)
        }
        val modeScale = when (p.mode) {
            PointerMode.PRECISION -> PRECISION_SCALE
            PointerMode.DESKTOP, PointerMode.GAMING -> 1f
        }
        out[0] = dx * gain * modeScale * p.xScale
        out[1] = dy * gain * modeScale * p.yScale
    }

    /**
     * Same as [transform] but returns whole-pixel deltas, carrying the fractional remainder
     * forward. out[0]/out[1] receive integers (as floats for array reuse).
     */
    fun transformToPixels(dx: Float, dy: Float, out: FloatArray) {
        transform(dx, dy, out)
        val x = out[0] + remX
        val y = out[1] + remY
        val ix = x.toInt()
        val iy = y.toInt()
        remX = x - ix
        remY = y - iy
        out[0] = ix.toFloat()
        out[1] = iy.toFloat()
    }

    private fun curveGain(speed: Float, p: SensitivityPreset): Float {
        if (speed <= 0f) return 1f
        val a = p.accelerationExponent.coerceIn(MIN_EXPONENT, MAX_EXPONENT)
        return when (val c = p.curve) {
            SensitivityCurve.Linear -> 1f
            // gain grows as speed^(a-1): a = 1 is flat, a = 1.5 doubles gain at 4x speed.
            SensitivityCurve.Exponential -> (speed.coerceAtLeast(1f)).pow(a - 1f).coerceAtMost(MAX_GAIN)
            is SensitivityCurve.CustomBezier -> bezierGain(speed, c).coerceIn(MIN_GAIN, MAX_GAIN)
        }
    }

    /**
     * Treats the bezier as a speed->output-speed response curve over [0, BEZIER_MAX_SPEED]
     * with endpoints fixed at (0,0) and (1,1). Gain = output/input at the current speed.
     */
    private fun bezierGain(speed: Float, c: SensitivityCurve.CustomBezier): Float {
        val x = (speed / BEZIER_MAX_SPEED).coerceIn(0f, 1f)
        if (x <= 0f) return 1f
        // Solve bezierX(t) = x by bisection (monotonic for control x in [0,1]).
        val p1x = c.p1x.coerceIn(0f, 1f)
        val p2x = c.p2x.coerceIn(0f, 1f)
        var lo = 0f
        var hi = 1f
        repeat(BISECTION_STEPS) {
            val mid = (lo + hi) / 2f
            if (cubic(mid, p1x, p2x) < x) lo = mid else hi = mid
        }
        val y = cubic((lo + hi) / 2f, c.p1y, c.p2y)
        return if (abs(x) < 1e-6f) 1f else y / x
    }

    /** One coordinate of a cubic bezier with endpoints 0 and 1. */
    private fun cubic(t: Float, c1: Float, c2: Float): Float {
        val u = 1f - t
        return 3f * u * u * t * c1 + 3f * u * t * t * c2 + t * t * t
    }

    private companion object {
        const val PRECISION_SCALE = 0.4f
        const val MIN_EXPONENT = 1f
        const val MAX_EXPONENT = 3f
        const val MIN_GAIN = 0.1f
        const val MAX_GAIN = 8f
        const val BEZIER_MAX_SPEED = 60f // counts per report treated as "full speed"
        const val BISECTION_STEPS = 12
    }
}
