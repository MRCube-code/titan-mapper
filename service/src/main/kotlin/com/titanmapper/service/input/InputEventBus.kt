package com.titanmapper.service.input

import com.titanmapper.service.api.InputEventStream
import com.titanmapper.core.model.RawInputEvent
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
 */
@Singleton
class InputEventBus @Inject constructor() : InputEventStream {

    private val _events = MutableSharedFlow<RawInputEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    override val events: SharedFlow<RawInputEvent> get() = _events

    private val _dropped = AtomicLong(0)
    val droppedCount: Long get() = _dropped.get()

    /** Non-suspending publish for use directly on a capture thread/callback. */
    fun publish(event: RawInputEvent) {
        if (!_events.tryEmit(event)) _dropped.incrementAndGet()
    }
}
