package com.titanmapper.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.titanmapper.core.database.entity.BindingEntity
import com.titanmapper.core.database.entity.DeviceEntity
import com.titanmapper.core.database.entity.EventLogEntity
import com.titanmapper.core.database.entity.MacroEntity
import com.titanmapper.core.database.entity.OverlayLayoutEntity
import com.titanmapper.core.database.entity.ProfileEntity
import com.titanmapper.core.database.entity.ProfileWithBindings
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
    @Transaction
    @Query("SELECT * FROM profiles ORDER BY priority DESC, name COLLATE NOCASE")
    fun observeProfiles(): Flow<List<ProfileWithBindings>>

    @Transaction
    @Query("SELECT * FROM profiles WHERE id = :id")
    fun observeProfile(id: String): Flow<ProfileWithBindings?>

    @Transaction
    @Query("SELECT * FROM profiles WHERE id = :id")
    suspend fun getProfile(id: String): ProfileWithBindings?

    @Transaction
    @Query("SELECT * FROM profiles")
    suspend fun getAllProfiles(): List<ProfileWithBindings>

    @Query("SELECT createdAt FROM profiles WHERE id = :id")
    suspend fun getCreatedAt(id: String): Long?

    @Upsert
    suspend fun upsertProfile(profile: ProfileEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBindings(bindings: List<BindingEntity>)

    @Query("DELETE FROM bindings WHERE profileId = :profileId")
    suspend fun deleteBindings(profileId: String)

    /** Atomic: a crash mid-save can never leave a profile with half its bindings. */
    @Transaction
    suspend fun replaceProfile(profile: ProfileEntity, bindings: List<BindingEntity>) {
        upsertProfile(profile)
        deleteBindings(profile.id)
        insertBindings(bindings)
    }

    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun deleteProfile(id: String)

    @Query("UPDATE profiles SET isActive = :active, updatedAt = :now WHERE id = :id")
    suspend fun setActive(id: String, active: Boolean, now: Long)

    @Query("SELECT COUNT(*) FROM profiles")
    suspend fun count(): Int
}

@Dao
interface MacroDao {
    @Query("SELECT * FROM macros ORDER BY name COLLATE NOCASE")
    fun observeMacros(): Flow<List<MacroEntity>>

    @Query("SELECT * FROM macros WHERE id = :id")
    suspend fun getMacro(id: String): MacroEntity?

    @Query("SELECT * FROM macros")
    suspend fun getAll(): List<MacroEntity>

    @Upsert
    suspend fun upsert(macro: MacroEntity)

    @Query("DELETE FROM macros WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface DeviceDao {
    @Query("SELECT * FROM devices ORDER BY lastSeenAt DESC")
    fun observeDevices(): Flow<List<DeviceEntity>>

    @Query("SELECT * FROM devices WHERE stableId = :id")
    suspend fun get(id: String): DeviceEntity?

    @Upsert
    suspend fun upsert(device: DeviceEntity)

    @Query("UPDATE devices SET nickname = :nickname WHERE stableId = :id")
    suspend fun setNickname(id: String, nickname: String?)

    @Query("DELETE FROM devices WHERE stableId = :id")
    suspend fun delete(id: String)
}

@Dao
interface OverlayDao {
    @Query("SELECT * FROM overlay_layouts WHERE profileId = :profileId AND orientation = :orientation")
    fun observe(profileId: String, orientation: String): Flow<OverlayLayoutEntity?>

    @Query("SELECT * FROM overlay_layouts")
    suspend fun getAll(): List<OverlayLayoutEntity>

    @Upsert
    suspend fun upsert(layout: OverlayLayoutEntity)
}

@Dao
interface EventLogDao {
    @Insert
    suspend fun insertAll(entries: List<EventLogEntity>)

    @Query("SELECT * FROM event_log ORDER BY id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<EventLogEntity>>

    @Query("SELECT * FROM event_log ORDER BY id DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<EventLogEntity>

    /** Ring-buffer behaviour: keeps only the newest [keep] rows so the table can't grow unbounded. */
    @Query("DELETE FROM event_log WHERE id NOT IN (SELECT id FROM event_log ORDER BY id DESC LIMIT :keep)")
    suspend fun trim(keep: Int)

    @Query("DELETE FROM event_log")
    suspend fun clear()
}
