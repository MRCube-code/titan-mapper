package com.titanmapper.service.pipeline

import com.titanmapper.service.api.PerformanceMonitor
import com.titanmapper.service.api.PerformanceSnapshot
import com.titanmapper.service.input.InputEventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PerformanceMonitorImpl @Inject constructor(
    private val bus: InputEventBus
) : PerformanceMonitor {

    private val _snapshot = MutableStateFlow(PerformanceSnapshot())
    override val snapshot: StateFlow<PerformanceSnapshot> get() = _snapshot

    private val eventsThisSecond = AtomicInteger(0)
    private val startedAt = System.currentTimeMillis()
    private var job: kotlinx.coroutines.Job? = null

    fun start(scope: CoroutineScope) {
        if (job != null) return
        scope.launch(Dispatchers.Default) { bus.events.collect { eventsThisSecond.incrementAndGet() } }
        job = scope.launch(Dispatchers.Default) {
            while (isActive) {
                kotlinx.coroutines.delay(1000)
                val runtime = Runtime.getRuntime()
                _snapshot.value = PerformanceSnapshot(
                    eventsPerSecond = eventsThisSecond.getAndSet(0),
                    droppedEvents = bus.droppedCount,
                    heapUsedMb = ((runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)).toInt(),
                    uptimeMillis = System.currentTimeMillis() - startedAt
                )
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
