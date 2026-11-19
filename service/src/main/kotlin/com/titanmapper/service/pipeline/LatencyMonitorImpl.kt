package com.titanmapper.service.pipeline

import com.titanmapper.service.api.LatencyMonitor
import com.titanmapper.service.api.LatencyStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Capture-to-dispatch latency sampling (Architecture §5). Zero overhead when [enabled] is
 * false: [record] becomes a single volatile-read no-op, so leaving it off in production profiles
 * costs nothing on the input hot path.
 */
@Singleton
class LatencyMonitorImpl @Inject constructor() : LatencyMonitor {

    private val enabled = AtomicBoolean(false)
    private val ring = LongArray(RING_SIZE)
    private var ringIndex = 0
    private var filled = 0
    private var sum = 0L
    private var count = 0L
    private var max = 0L

    private val _stats = MutableStateFlow(LatencyStats())
    override val stats: StateFlow<LatencyStats> get() = _stats

    fun setEnabled(value: Boolean) {
        enabled.set(value)
        if (!value) reset()
    }

    /** Called from the pipeline's single consumer coroutine — not thread-safe for concurrent callers. */
    fun record(latencyNanos: Long) {
        if (!enabled.get()) return
        val micros = latencyNanos / 1000
        ring[ringIndex] = micros
        ringIndex = (ringIndex + 1) % RING_SIZE
        filled = minOf(filled + 1, RING_SIZE)
        sum += micros
        count++
        if (micros > max) max = micros
        _stats.value = LatencyStats(
            sampleCount = count,
            lastMicros = micros,
            averageMicros = sum / count,
            p95Micros = percentile95(),
            maxMicros = max
        )
    }

    override fun reset() {
        ring.fill(0); ringIndex = 0; filled = 0; sum = 0; count = 0; max = 0
        _stats.value = LatencyStats()
    }

    private fun percentile95(): Long {
        if (filled == 0) return 0
        val sorted = ring.copyOf(filled).also { it.sort() }
        val index = ((sorted.size - 1) * 0.95).toInt()
        return sorted[index]
    }

    private companion object {
        const val RING_SIZE = 512
    }
}
