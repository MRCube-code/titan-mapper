package com.titanmapper.service.tier

import com.titanmapper.core.model.CaptureTier
import com.titanmapper.service.engine.OutputDispatchSink

/**
 * One permission tier's capture + dispatch pair (Architecture §4.2-§4.5). [PipelineCoordinator]
 * holds exactly one active backend at a time and swaps it when the user changes tiers or the
 * active one becomes unhealthy.
 */
interface TierBackend {
    val tier: CaptureTier
    val sink: OutputDispatchSink

    /** @return true if capture actually started. False (not an exception) on a permission/availability failure. */
    suspend fun start(): Boolean

    /** Must be safe to call even if [start] was never called or already failed. */
    fun stop()

    /** Re-checked periodically by the coordinator; a false here triggers an automatic downgrade. */
    fun isHealthy(): Boolean
}
