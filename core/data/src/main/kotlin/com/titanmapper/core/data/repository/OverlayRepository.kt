package com.titanmapper.core.data.repository

import com.titanmapper.core.common.DataResult
import com.titanmapper.core.common.IoDispatcher
import com.titanmapper.core.common.safeCall
import com.titanmapper.core.data.EntityMapper
import com.titanmapper.core.database.dao.OverlayDao
import com.titanmapper.core.model.OverlayLayout
import com.titanmapper.core.model.ScreenOrientation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

interface OverlayRepository {
    /** Emits an empty layout when none has been saved yet, so editors never deal with null. */
    fun observe(profileId: String, orientation: ScreenOrientation): Flow<OverlayLayout>
    suspend fun getAll(): List<OverlayLayout>
    suspend fun save(layout: OverlayLayout): DataResult<Unit>
}

@Singleton
class OverlayRepositoryImpl @Inject constructor(
    private val dao: OverlayDao,
    private val mapper: EntityMapper,
    @IoDispatcher private val io: CoroutineDispatcher
) : OverlayRepository {

    override fun observe(profileId: String, orientation: ScreenOrientation): Flow<OverlayLayout> =
        dao.observe(profileId, orientation.name).map { row ->
            row?.let(mapper::toModel) ?: OverlayLayout.empty(profileId, orientation)
        }

    override suspend fun getAll(): List<OverlayLayout> = withContext(io) {
        dao.getAll().mapNotNull(mapper::toModel)
    }

    override suspend fun save(layout: OverlayLayout): DataResult<Unit> = withContext(io) {
        safeCall("Couldn't save the overlay layout") {
            val clamped = layout.copy(controls = layout.controls.map { it.clamped() })
            dao.upsert(mapper.toEntity(clamped))
        }
    }
}
