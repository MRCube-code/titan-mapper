package com.titanmapper.core.data.repository

import com.titanmapper.core.common.ApplicationScope
import com.titanmapper.core.common.IoDispatcher
import com.titanmapper.core.data.EntityMapper
import com.titanmapper.core.data.settings.SettingsRepository
import com.titanmapper.core.database.dao.EventLogDao
import com.titanmapper.core.model.EventLogEntry
import com.titanmapper.core.model.LogLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Diagnostics event log. [log] never blocks and never throws: it drops the oldest queued entry
 * when overwhelmed, so logging can't slow down the input hot path. A single writer coroutine
 * batches inserts and trims the table to the user's configured capacity (ring buffer).
 */
interface EventLogRepository {
    fun log(level: LogLevel, category: String, message: String, deviceName: String? = null, latencyNanos: Long? = null)
    fun observeRecent(limit: Int = 500): Flow<List<EventLogEntry>>
    suspend fun clear()
    suspend fun exportText(limit: Int = 5_000): String
}

@Singleton
class EventLogRepositoryImpl @Inject constructor(
    private val dao: EventLogDao,
    private val mapper: EntityMapper,
    private val settings: SettingsRepository,
    @ApplicationScope scope: CoroutineScope,
    @IoDispatcher private val io: CoroutineDispatcher
) : EventLogRepository {

    private val queue = Channel<EventLogEntry>(
        capacity = QUEUE_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    init {
        scope.launch(io) {
            while (true) {
                try {
                    val batch = ArrayList<EventLogEntry>(MAX_BATCH)
                    batch += queue.receive()
                    while (batch.size < MAX_BATCH) {
                        batch += queue.tryReceive().getOrNull() ?: break
                    }
                    dao.insertAll(batch.map(mapper::toEntity))
                    dao.trim(settings.settings.first().eventLogCapacity)
                    delay(COALESCE_MS)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Logging is best-effort: a failed insert must never take the app down.
                    delay(COALESCE_MS)
                }
            }
        }
    }

    override fun log(level: LogLevel, category: String, message: String, deviceName: String?, latencyNanos: Long?) {
        queue.trySend(
            EventLogEntry(
                timestampMillis = System.currentTimeMillis(),
                level = level,
                category = category,
                message = message.take(MAX_MESSAGE),
                deviceName = deviceName,
                latencyNanos = latencyNanos
            )
        )
    }

    override fun observeRecent(limit: Int): Flow<List<EventLogEntry>> =
        dao.observeRecent(limit).map { rows -> rows.map(mapper::toModel) }

    override suspend fun clear() = withContext(io) { dao.clear() }

    override suspend fun exportText(limit: Int): String = withContext(io) {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        dao.getRecent(limit).asReversed().joinToString("\n") { e ->
            val extra = buildString {
                e.deviceName?.let { append(" [").append(it).append(']') }
                e.latencyNanos?.let { append(" (").append(it / 1000).append(" us)") }
            }
            "${fmt.format(Date(e.timestampMillis))} ${e.level} ${e.category}: ${e.message}$extra"
        }
    }

    private companion object {
        const val QUEUE_CAPACITY = 1024
        const val MAX_BATCH = 200
        const val MAX_MESSAGE = 500
        const val COALESCE_MS = 250L
    }
}
