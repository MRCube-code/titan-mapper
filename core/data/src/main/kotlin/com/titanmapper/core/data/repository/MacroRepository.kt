package com.titanmapper.core.data.repository

import com.titanmapper.core.common.DataResult
import com.titanmapper.core.common.IoDispatcher
import com.titanmapper.core.common.safeCall
import com.titanmapper.core.data.EntityMapper
import com.titanmapper.core.database.dao.MacroDao
import com.titanmapper.core.model.MacroDefinition
import com.titanmapper.core.model.MacroValidator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

interface MacroRepository {
    fun observeMacros(): Flow<List<MacroDefinition>>
    suspend fun get(id: String): MacroDefinition?
    suspend fun getAll(): List<MacroDefinition>
    suspend fun save(macro: MacroDefinition): DataResult<Unit>
    suspend fun delete(id: String): DataResult<Unit>
}

@Singleton
class MacroRepositoryImpl @Inject constructor(
    private val dao: MacroDao,
    private val mapper: EntityMapper,
    @IoDispatcher private val io: CoroutineDispatcher
) : MacroRepository {

    override fun observeMacros(): Flow<List<MacroDefinition>> =
        dao.observeMacros().map { rows -> rows.mapNotNull(mapper::toModel) }

    override suspend fun get(id: String): MacroDefinition? = withContext(io) {
        dao.getMacro(id)?.let(mapper::toModel)
    }

    override suspend fun getAll(): List<MacroDefinition> = withContext(io) {
        dao.getAll().mapNotNull(mapper::toModel)
    }

    override suspend fun save(macro: MacroDefinition): DataResult<Unit> {
        val errors = MacroValidator.validate(macro)
        if (errors.isNotEmpty()) return DataResult.Error(errors.joinToString("; "))
        return withContext(io) {
            safeCall("Couldn't save the macro") { dao.upsert(mapper.toEntity(macro, System.currentTimeMillis())) }
        }
    }

    override suspend fun delete(id: String): DataResult<Unit> = withContext(io) {
        safeCall("Couldn't delete the macro") { dao.delete(id) }
    }
}
