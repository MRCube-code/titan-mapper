package com.titanmapper.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.titanmapper.core.data.enumOrDefault
import com.titanmapper.core.model.AppSettings
import com.titanmapper.core.model.PermissionTier
import com.titanmapper.core.model.ProfileColor
import com.titanmapper.core.model.ThemeConfig
import com.titanmapper.core.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun update(transform: (AppSettings) -> AppSettings)
}

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val store: DataStore<Preferences>
) : SettingsRepository {

    private object Keys {
        val themeMode = stringPreferencesKey("theme_mode")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val accent = stringPreferencesKey("accent")
        val compact = booleanPreferencesKey("compact_pointer")
        val tier = stringPreferencesKey("preferred_tier")
        val autoSwitch = booleanPreferencesKey("auto_profile_switch")
        val logCapacity = intPreferencesKey("log_capacity")
        val latency = booleanPreferencesKey("latency_monitor")
        val idleMinutes = intPreferencesKey("idle_teardown_minutes")
        val boot = booleanPreferencesKey("start_on_boot")
        val overlayMenu = booleanPreferencesKey("show_overlay_menu")
        val updateUrl = stringPreferencesKey("update_url")
    }

    override val settings: Flow<AppSettings> = store.data
        .catch { if (it is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw it }
        .map { it.toSettings() }
        .distinctUntilChanged()

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { prefs ->
            val next = sanitize(transform(prefs.toSettings()))
            prefs[Keys.themeMode] = next.theme.mode.name
            prefs[Keys.dynamicColor] = next.theme.useDynamicColor
            prefs[Keys.accent] = next.theme.accent.name
            prefs[Keys.compact] = next.theme.compactWhenPointerPresent
            prefs[Keys.tier] = next.preferredTier.name
            prefs[Keys.autoSwitch] = next.autoProfileSwitch
            prefs[Keys.logCapacity] = next.eventLogCapacity
            prefs[Keys.latency] = next.latencyMonitorEnabled
            prefs[Keys.idleMinutes] = next.idleTeardownMinutes
            prefs[Keys.boot] = next.startServiceOnBoot
            prefs[Keys.overlayMenu] = next.showOverlayMenu
            prefs[Keys.updateUrl] = next.updateChannelUrl
        }
    }

    private fun sanitize(s: AppSettings) = s.copy(
        eventLogCapacity = s.eventLogCapacity.coerceIn(AppSettings.MIN_LOG_CAPACITY, AppSettings.MAX_LOG_CAPACITY),
        idleTeardownMinutes = s.idleTeardownMinutes.coerceIn(1, 120)
    )

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            theme = ThemeConfig(
                mode = enumOrDefault(this[Keys.themeMode] ?: "", d.theme.mode),
                useDynamicColor = this[Keys.dynamicColor] ?: d.theme.useDynamicColor,
                accent = enumOrDefault(this[Keys.accent] ?: "", ProfileColor.BLUE),
                compactWhenPointerPresent = this[Keys.compact] ?: d.theme.compactWhenPointerPresent
            ),
            preferredTier = enumOrDefault(this[Keys.tier] ?: "", PermissionTier.STANDARD),
            autoProfileSwitch = this[Keys.autoSwitch] ?: d.autoProfileSwitch,
            eventLogCapacity = this[Keys.logCapacity] ?: d.eventLogCapacity,
            latencyMonitorEnabled = this[Keys.latency] ?: d.latencyMonitorEnabled,
            idleTeardownMinutes = this[Keys.idleMinutes] ?: d.idleTeardownMinutes,
            startServiceOnBoot = this[Keys.boot] ?: d.startServiceOnBoot,
            showOverlayMenu = this[Keys.overlayMenu] ?: d.showOverlayMenu,
            updateChannelUrl = this[Keys.updateUrl] ?: d.updateChannelUrl
        )
    }
}
