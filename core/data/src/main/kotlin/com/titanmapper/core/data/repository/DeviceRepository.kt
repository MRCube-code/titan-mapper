package com.titanmapper.core.data.repository

import com.titanmapper.core.common.IoDispatcher
import com.titanmapper.core.data.EntityMapper
import com.titanmapper.core.database.dao.DeviceDao
import com.titanmapper.core.model.DeviceFingerprint
import com.titanmapper.core.model.DeviceRecord
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Known devices (persisted) combined with which ones are plugged in right now (runtime only,
 * so a crash can never leave a device stuck "connected").
 */
interface DeviceRepository {
    fun observeDevices(): Flow<List<DeviceRecord>>
    suspend fun onDeviceConnected(fingerprint: DeviceFingerprint)
    fun onDeviceDisconnected(stableId: String)
    fun clearConnected()
    suspend fun setNickname(stableId: String, nickname: String?)
    suspend fun forget(stableId: String)
}

@Singleton
class DeviceRepositoryImpl @Inject constructor(
    private val dao: DeviceDao,
    private val mapper: EntityMapper,
    @IoDispatcher private val io: CoroutineDispatcher
) : DeviceRepository {

    private val connected = MutableStateFlow<Set<String>>(emptySet())

    override fun observeDevices(): Flow<List<DeviceRecord>> =
        combine(dao.observeDevices(), connected) { rows, live ->
            rows.map {
                DeviceRecord(
                    fingerprint = mapper.toFingerprint(it),
                    nickname = it.nickname,
                    connected = it.stableId in live,
                    lastSeenMillis = it.lastSeenAt
                )
            }.sortedWith(compareByDescending<DeviceRecord> { it.connected }.thenByDescending { it.lastSeenMillis })
        }

    override suspend fun onDeviceConnected(fingerprint: DeviceFingerprint) {
        withContext(io) {
            val existing = dao.get(fingerprint.stableId)
            dao.upsert(mapper.toEntity(fingerprint, existing?.nickname, System.currentTimeMillis()))
        }
        connected.update { it + fingerprint.stableId }
    }

    override fun onDeviceDisconnected(stableId: String) {
        connected.update { it - stableId }
    }

    override fun clearConnected() {
        connected.value = emptySet()
    }

    override suspend fun setNickname(stableId: String, nickname: String?) = withContext(io) {
        dao.setNickname(stableId, nickname?.trim()?.take(60)?.ifBlank { null })
    }

    override suspend fun forget(stableId: String) = withContext(io) {
        dao.delete(stableId)
        connected.update { it - stableId }
    }
}
