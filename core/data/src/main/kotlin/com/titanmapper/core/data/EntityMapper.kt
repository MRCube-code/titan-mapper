package com.titanmapper.core.data

import android.util.Log
import com.titanmapper.core.database.entity.BindingEntity
import com.titanmapper.core.database.entity.DeviceEntity
import com.titanmapper.core.database.entity.EventLogEntity
import com.titanmapper.core.database.entity.MacroEntity
import com.titanmapper.core.database.entity.OverlayLayoutEntity
import com.titanmapper.core.database.entity.ProfileEntity
import com.titanmapper.core.database.entity.ProfileWithBindings
import com.titanmapper.core.model.ActionMode
import com.titanmapper.core.model.Binding
import com.titanmapper.core.model.CaptureTier
import com.titanmapper.core.model.DeviceClass
import com.titanmapper.core.model.DeviceFingerprint
import com.titanmapper.core.model.EventLogEntry
import com.titanmapper.core.model.InputTrigger
import com.titanmapper.core.model.LogLevel
import com.titanmapper.core.model.MacroDefinition
import com.titanmapper.core.model.MappedAction
import com.titanmapper.core.model.MappingProfile
import com.titanmapper.core.model.OverlayLayout
import com.titanmapper.core.model.ProfileColor
import com.titanmapper.core.model.SensitivityPreset
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "EntityMapper"

internal inline fun <reified E : Enum<E>> enumOrDefault(name: String, default: E): E =
    enumValues<E>().firstOrNull { it.name == name } ?: default

/**
 * Translates between storage rows and domain models. Corrupt JSON in one row degrades
 * gracefully (that binding is skipped, bad sensitivity falls back to defaults) instead of
 * crashing the whole profile list: a bad row must never brick the app.
 */
@Singleton
class EntityMapper @Inject constructor(private val json: Json) {

    fun toModel(row: ProfileWithBindings): MappingProfile {
        val p = row.profile
        val bindings = row.bindings.sortedBy { it.sortOrder }.mapNotNull { toBinding(it) }
        val sensitivity = runCatching { json.decodeFromString<SensitivityPreset>(p.sensitivityJson) }
            .getOrElse {
                Log.w(TAG, "Bad sensitivity JSON for profile ${p.id}; using defaults", it)
                SensitivityPreset.DEFAULT
            }
        return MappingProfile(
            id = p.id,
            name = p.name,
            targetPackageName = p.targetPackage,
            deviceFingerprintId = p.deviceFingerprintId,
            bindings = bindings,
            sensitivity = sensitivity,
            isActive = p.isActive,
            priority = p.priority,
            colorTag = enumOrDefault(p.colorTag, ProfileColor.BLUE),
            permissionTier = enumOrDefault(p.permissionTier, CaptureTier.STANDARD),
            schemaVersion = p.schemaVersion
        )
    }

    private fun toBinding(b: BindingEntity): Binding? = runCatching {
        Binding(
            id = b.id,
            trigger = json.decodeFromString<InputTrigger>(b.triggerJson),
            action = json.decodeFromString<MappedAction>(b.actionJson),
            mode = enumOrDefault(b.mode, ActionMode.TAP),
            repeatIntervalMillis = b.repeatIntervalMillis,
            isEnabled = b.isEnabled
        )
    }.getOrElse {
        Log.w(TAG, "Skipping undecodable binding ${b.id}", it)
        null
    }

    fun toEntities(profile: MappingProfile, createdAt: Long, now: Long): Pair<ProfileEntity, List<BindingEntity>> {
        val entity = ProfileEntity(
            id = profile.id,
            name = profile.name,
            targetPackage = profile.targetPackageName,
            deviceFingerprintId = profile.deviceFingerprintId,
            isActive = profile.isActive,
            priority = profile.priority,
            colorTag = profile.colorTag.name,
            permissionTier = profile.permissionTier.name,
            sensitivityJson = json.encodeToString(profile.sensitivity),
            schemaVersion = MappingProfile.CURRENT_SCHEMA_VERSION,
            createdAt = createdAt,
            updatedAt = now
        )
        val bindings = profile.bindings.mapIndexed { i, b ->
            BindingEntity(
                id = b.id,
                profileId = profile.id,
                triggerJson = json.encodeToString(b.trigger),
                actionJson = json.encodeToString(b.action),
                mode = b.mode.name,
                repeatIntervalMillis = b.repeatIntervalMillis,
                isEnabled = b.isEnabled,
                sortOrder = i
            )
        }
        return entity to bindings
    }

    fun toModel(e: MacroEntity): MacroDefinition? = runCatching {
        MacroDefinition(
            id = e.id,
            name = e.name,
            steps = json.decodeFromString(e.stepsJson),
            isGlobal = e.isGlobal,
            schemaVersion = e.schemaVersion
        )
    }.getOrElse {
        Log.w(TAG, "Skipping undecodable macro ${e.id}", it)
        null
    }

    fun toEntity(m: MacroDefinition, now: Long) = MacroEntity(
        id = m.id,
        name = m.name,
        stepsJson = json.encodeToString(m.steps),
        isGlobal = m.isGlobal,
        schemaVersion = MacroDefinition.CURRENT_SCHEMA_VERSION,
        updatedAt = now
    )

    fun toFingerprint(e: DeviceEntity) = DeviceFingerprint(
        stableId = e.stableId,
        vendorId = e.vendorId,
        productId = e.productId,
        deviceName = e.deviceName,
        deviceClass = enumOrDefault(e.deviceClass, DeviceClass.UNKNOWN)
    )

    fun toEntity(f: DeviceFingerprint, nickname: String?, now: Long) = DeviceEntity(
        stableId = f.stableId,
        vendorId = f.vendorId,
        productId = f.productId,
        deviceName = f.deviceName,
        deviceClass = f.deviceClass.name,
        nickname = nickname,
        lastSeenAt = now
    )

    fun toModel(e: OverlayLayoutEntity): OverlayLayout? =
        runCatching { json.decodeFromString<OverlayLayout>(e.layoutJson) }.getOrNull()

    fun toEntity(l: OverlayLayout) = OverlayLayoutEntity(
        profileId = l.profileId,
        orientation = l.orientation.name,
        layoutJson = json.encodeToString(l)
    )

    fun toModel(e: EventLogEntity) = EventLogEntry(
        id = e.id,
        timestampMillis = e.timestampMillis,
        level = enumOrDefault(e.level, LogLevel.INFO),
        category = e.category,
        message = e.message,
        deviceName = e.deviceName,
        latencyNanos = e.latencyNanos
    )

    fun toEntity(e: EventLogEntry) = EventLogEntity(
        timestampMillis = e.timestampMillis,
        level = e.level.name,
        category = e.category,
        message = e.message,
        deviceName = e.deviceName,
        latencyNanos = e.latencyNanos
    )
}
