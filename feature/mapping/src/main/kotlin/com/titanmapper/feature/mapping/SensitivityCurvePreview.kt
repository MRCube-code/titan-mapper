package com.titanmapper.feature.mapping

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.titanmapper.core.model.SensitivityCurve
import com.titanmapper.core.model.SensitivityPreset
import kotlin.math.pow

/**
 * Draws speed (x) vs. output gain (y) for the current curve. Deliberately re-implements the
 * same formula as [com.titanmapper.service.engine.PointerTransform] rather than depending on
 * :service from a UI module (Architecture §3: UI never depends on service internals) — if the
 * two drift apart, [com.titanmapper.service.engine.PointerTransformTest] is the contract that
 * catches it on the engine side.
 */
@Composable
fun SensitivityCurvePreview(preset: SensitivityPreset, modifier: Modifier = Modifier) {
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier = modifier.fillMaxWidth().height(120.dp)) {
        val steps = 64
        // Grid
        for (i in 0..4) {
            val y = size.height * i / 4f
            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }
        val points = (0..steps).map { i ->
            val speed = i / steps.toFloat() * MAX_SPEED
            val gain = gainFor(speed, preset)
            val x = i / steps.toFloat() * size.width
            val y = size.height - (gain / MAX_GAIN).coerceIn(0f, 1f) * size.height
            Offset(x, y)
        }
        for (i in 0 until points.size - 1) {
            drawLine(lineColor, points[i], points[i + 1], strokeWidth = 4f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        }
    }
}

private const val MAX_SPEED = 60f
private const val MAX_GAIN = 8f

private fun gainFor(speed: Float, p: SensitivityPreset): Float {
    if (p.rawInputEnabled) return 1f
    val a = p.accelerationExponent.coerceIn(1f, 3f)
    return when (val c = p.curve) {
        SensitivityCurve.Linear -> 1f
        SensitivityCurve.Exponential -> speed.coerceAtLeast(1f).pow(a - 1f).coerceAtMost(MAX_GAIN)
        is SensitivityCurve.CustomBezier -> bezierGain(speed, c)
    }
}

private fun bezierGain(speed: Float, c: SensitivityCurve.CustomBezier): Float {
    val x = (speed / MAX_SPEED).coerceIn(0f, 1f)
    if (x <= 0f) return 1f
    var lo = 0f; var hi = 1f
    repeat(12) {
        val mid = (lo + hi) / 2f
        if (cubic(mid, c.p1x.coerceIn(0f, 1f), c.p2x.coerceIn(0f, 1f)) < x) lo = mid else hi = mid
    }
    val y = cubic((lo + hi) / 2f, c.p1y, c.p2y)
    return (y / x).coerceIn(0.1f, MAX_GAIN)
}

private fun cubic(t: Float, c1: Float, c2: Float): Float {
    val u = 1f - t
    return 3f * u * u * t * c1 + 3f * u * t * t * c2 + t * t * t
}
