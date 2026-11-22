package com.titanmapper.service.input

import com.titanmapper.service.api.InputEventStream
import com.titanmapper.core.model.RawInputEvent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Broadcast hub described in Architecture §4.6. A capture adapter's hot callback calls
 * [publish], which never suspends and never throws: on overflow the oldest buffered event is
 * dropped (DROP_OLDEST) rather than blocking the capture thread, because a blocked capture
 * thread is worse than a lost diagnostics sample.
 *
 * DROP_OLDEST means [MutableSharedFlow.tryEmit] itself never reports failure -- it "succeeds" by
 * silently evicting the oldest buffered event instead of rejecting the new one -- so there is no
 * boolean to check here. [droppedCount] is derived instead: [publish] tracks how many events have
 * ever been handed to the flow, [noteConsumed] (called once per event by the single actor
 * coroutine that collects [events], see `PipelineCoordinator`) tracks how many have actually been
 * pulled back out, and whenever the gap between those two exceeds [BUFFER_CAPACITY] the excess
 * must already have been evicted -- more items were published than the buffer can hold at once.
 * This lets a consumer detect real event loss (and, critically, trigger a releaseAll() safety net
 * so a lost key-up never leaves a mapped key "held" forever) instead of trusting a counter that
 * would otherwise always read zero.
 */
@Singleton
class InputEventBus @Inject constructor() : InputEventStream {

    private val _events = MutableSharedFlow<RawInputEvent>(
        replay = 0,
        extraBufferCapacity = BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val events: SharedFlow<RawInputEvent> get() = _events

    private val published = AtomicLong(0)
    private val consumed = AtomicLong(0)
    private val _dropped = AtomicLong(0)
    val droppedCount: Long get() = _dropped.get()

    /** Non-suspending publish for use directly on a capture thread/callback. */
    fun publish(event: RawInputEvent) {
        val publishedSoFar = published.incrementAndGet()
        _events.tryEmit(event)
        val overflow = publishedSoFar - consumed.get() - BUFFER_CAPACITY
        if (overflow > 0) _dropped.addAndGet(overflow)
    }

    /**
     * Called by [events]'s single collector once per event it actually pulls out, so [publish]
     * can tell genuine loss apart from the normal, bounded lag of a consumer that's merely
     * mid-dispatch on the previous event.
     */
    fun noteConsumed() {
        consumed.incrementAndGet()
    }

    private companion object {
        const val BUFFER_CAPACITY = 64
    }
}
