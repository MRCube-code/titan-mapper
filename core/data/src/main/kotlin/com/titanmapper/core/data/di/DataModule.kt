package com.titanmapper.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.titanmapper.core.common.ApplicationScope
import com.titanmapper.core.common.DefaultDispatcher
import com.titanmapper.core.common.IoDispatcher
import com.titanmapper.core.data.TitanJson
import com.titanmapper.core.data.repository.DeviceRepository
import com.titanmapper.core.data.repository.DeviceRepositoryImpl
import com.titanmapper.core.data.repository.EventLogRepository
import com.titanmapper.core.data.repository.EventLogRepositoryImpl
import com.titanmapper.core.data.repository.MacroRepository
import com.titanmapper.core.data.repository.MacroRepositoryImpl
import com.titanmapper.core.data.repository.OverlayRepository
import com.titanmapper.core.data.repository.OverlayRepositoryImpl
import com.titanmapper.core.data.repository.ProfileRepository
import com.titanmapper.core.data.repository.ProfileRepositoryImpl
import com.titanmapper.core.data.settings.SettingsRepository
import com.titanmapper.core.data.settings.SettingsRepositoryImpl
import com.titanmapper.core.database.TitanDatabase
import com.titanmapper.core.database.dao.DeviceDao
import com.titanmapper.core.database.dao.EventLogDao
import com.titanmapper.core.database.dao.MacroDao
import com.titanmapper.core.database.dao.OverlayDao
import com.titanmapper.core.database.dao.ProfileDao
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataProvidesModule {
    @Provides @Singleton
    fun database(@ApplicationContext context: Context): TitanDatabase =
        // No destructive-migration fallback: user profiles must never be silently wiped.
        Room.databaseBuilder(context, TitanDatabase::class.java, TitanDatabase.NAME).build()

    @Provides fun profileDao(db: TitanDatabase): ProfileDao = db.profileDao()
    @Provides fun macroDao(db: TitanDatabase): MacroDao = db.macroDao()
    @Provides fun deviceDao(db: TitanDatabase): DeviceDao = db.deviceDao()
    @Provides fun overlayDao(db: TitanDatabase): OverlayDao = db.overlayDao()
    @Provides fun eventLogDao(db: TitanDatabase): EventLogDao = db.eventLogDao()

    @Provides @Singleton
    fun json(): Json = TitanJson.instance

    @Provides @IoDispatcher
    fun io(): CoroutineDispatcher = Dispatchers.IO

    @Provides @DefaultDispatcher
    fun default(): CoroutineDispatcher = Dispatchers.Default

    @Provides @Singleton @ApplicationScope
    fun applicationScope(@DefaultDispatcher dispatcher: CoroutineDispatcher): CoroutineScope =
        CoroutineScope(SupervisorJob() + dispatcher)

    @Provides @Singleton
    fun settingsStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("titan_settings") }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryBindingsModule {
    @Binds abstract fun profiles(impl: ProfileRepositoryImpl): ProfileRepository
    @Binds abstract fun macros(impl: MacroRepositoryImpl): MacroRepository
    @Binds abstract fun devices(impl: DeviceRepositoryImpl): DeviceRepository
    @Binds abstract fun overlays(impl: OverlayRepositoryImpl): OverlayRepository
    @Binds abstract fun eventLog(impl: EventLogRepositoryImpl): EventLogRepository
    @Binds abstract fun settings(impl: SettingsRepositoryImpl): SettingsRepository
}
