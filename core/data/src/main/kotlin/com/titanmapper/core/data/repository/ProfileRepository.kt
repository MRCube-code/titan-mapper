package com.titanmapper.core.data.repository

import com.titanmapper.core.common.DataResult
import com.titanmapper.core.common.IoDispatcher
import com.titanmapper.core.common.safeCall
import com.titanmapper.core.data.EntityMapper
import com.titanmapper.core.database.dao.ProfileDao
import com.titanmapper.core.model.MappingProfile
import com.titanmapper.core.model.ProfileResolver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

interface ProfileRepository {
    fun observeProfiles(): Flow<List<MappingProfile>>
    fun observeProfile(id: String): Flow<MappingProfile?>

    /** Automatic profile switching: emits the winning profile whenever it changes. */
    fun observeResolved(deviceStableId: String?, foregroundPackage: String?): Flow<MappingProfile?>

    suspend fun get(id: String): MappingProfile?
    suspend fun save(profile: MappingProfile): DataResult<Unit>
    suspend fun delete(id: String): DataResult<Unit>
    suspend fun setEnabled(id: String, enabled: Boolean): DataResult<Unit>
    suspend fun duplicate(id: String): DataResult<String>

    companion object {
        const val MAX_BINDINGS = 2_000
        const val MAX_NAME_LENGTH = 100
    }
}

@Singleton
class ProfileRepositoryImpl @Inject constructor(
    private val dao: ProfileDao,
    private val mapper: EntityMapper,
    @IoDispatcher private val io: CoroutineDispatcher
) : ProfileRepository {

    override fun observeProfiles(): Flow<List<MappingProfile>> =
        dao.observeProfiles().map { rows -> rows.map(mapper::toModel) }

    override fun observeProfile(id: String): Flow<MappingProfile?> =
        dao.observeProfile(id).map { row -> row?.let(mapper::toModel) }

    override fun observeResolved(deviceStableId: String?, foregroundPackage: String?): Flow<MappingProfile?> =
        observeProfiles()
            .map { ProfileResolver.resolve(it, deviceStableId, foregroundPackage) }
            .distinctUntilChanged()

    override suspend fun get(id: String): MappingProfile? = withContext(io) {
        dao.getProfile(id)?.let(mapper::toModel)
    }

    override suspend fun save(profile: MappingProfile): DataResult<Unit> {
        if (profile.name.isBlank()) return DataResult.Error("Profile name can't be empty")
        if (profile.name.length > ProfileRepository.MAX_NAME_LENGTH) {
            return DataResult.Error("Profile name is too long")
        }
        if (profile.bindings.size > ProfileRepository.MAX_BINDINGS) {
            return DataResult.Error("A profile can have at most ${ProfileRepository.MAX_BINDINGS} bindings")
        }
        return withContext(io) {
            safeCall("Couldn't save the profile") {
                val now = System.currentTimeMillis()
                val created = dao.getCreatedAt(profile.id) ?: now
                val (entity, bindings) = mapper.toEntities(profile, created, now)
                dao.replaceProfile(entity, bindings)
            }
        }
    }

    override suspend fun delete(id: String): DataResult<Unit> = withContext(io) {
        safeCall("Couldn't delete the profile") { dao.deleteProfile(id) }
    }

    override suspend fun setEnabled(id: String, enabled: Boolean): DataResult<Unit> = withContext(io) {
        safeCall("Couldn't update the profile") { dao.setActive(id, enabled, System.currentTimeMillis()) }
    }

    override suspend fun duplicate(id: String): DataResult<String> = withContext(io) {
        val source = dao.getProfile(id)?.let(mapper::toModel)
            ?: return@withContext DataResult.Error("Profile not found")
        val copy = source.copy(
            id = UUID.randomUUID().toString(),
            name = "${source.name} (copy)".take(ProfileRepository.MAX_NAME_LENGTH),
            isActive = false,
            bindings = source.bindings.map { it.copy(id = UUID.randomUUID().toString()) }
        )
        save(copy).map { copy.id }
    }
}
