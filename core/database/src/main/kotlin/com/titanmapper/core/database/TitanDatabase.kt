package com.titanmapper.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.titanmapper.core.database.dao.DeviceDao
import com.titanmapper.core.database.dao.EventLogDao
import com.titanmapper.core.database.dao.MacroDao
import com.titanmapper.core.database.dao.OverlayDao
import com.titanmapper.core.database.dao.ProfileDao
import com.titanmapper.core.database.entity.BindingEntity
import com.titanmapper.core.database.entity.DeviceEntity
import com.titanmapper.core.database.entity.EventLogEntity
import com.titanmapper.core.database.entity.MacroEntity
import com.titanmapper.core.database.entity.OverlayLayoutEntity
import com.titanmapper.core.database.entity.ProfileEntity

@Database(
    entities = [
        ProfileEntity::class,
        BindingEntity::class,
        MacroEntity::class,
        DeviceEntity::class,
        OverlayLayoutEntity::class,
        EventLogEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class TitanDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun macroDao(): MacroDao
    abstract fun deviceDao(): DeviceDao
    abstract fun overlayDao(): OverlayDao
    abstract fun eventLogDao(): EventLogDao

    companion object {
        const val NAME = "titan_mapper.db"
    }
}
