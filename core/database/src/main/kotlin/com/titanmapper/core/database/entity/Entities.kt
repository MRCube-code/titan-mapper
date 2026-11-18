package com.titanmapper.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

/**
 * Storage-shaped rows. Polymorphic structures (triggers, actions, macro steps, sensitivity,
 * overlay controls) are stored as JSON strings produced by kotlinx.serialization in :core:data;
 * the database layer deliberately knows nothing about their shape.
 */
@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val targetPackage: String?,
    val deviceFingerprintId: String?,
    val isActive: Boolean,
    val priority: Int,
    val colorTag: String,
    val permissionTier: String,
    val sensitivityJson: String,
    val schemaVersion: Int,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "bindings",
    foreignKeys = [ForeignKey(
        entity = ProfileEntity::class,
        parentColumns = ["id"],
        childColumns = ["profileId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("profileId")]
)
data class BindingEntity(
    @PrimaryKey val id: String,
    val profileId: String,
    val triggerJson: String,
    val actionJson: String,
    val mode: String,
    val repeatIntervalMillis: Long,
    val isEnabled: Boolean,
    val sortOrder: Int
)

data class ProfileWithBindings(
    @Embedded val profile: ProfileEntity,
    @Relation(parentColumn = "id", entityColumn = "profileId")
    val bindings: List<BindingEntity>
)

@Entity(tableName = "macros")
data class MacroEntity(
    @PrimaryKey val id: String,
    val name: String,
    val stepsJson: String,
    val isGlobal: Boolean,
    val schemaVersion: Int,
    val updatedAt: Long
)

@Entity(tableName = "devices")
data class DeviceEntity(
    @PrimaryKey val stableId: String,
    val vendorId: Int,
    val productId: Int,
    val deviceName: String,
    val deviceClass: String,
    val nickname: String?,
    val lastSeenAt: Long
)

@Entity(
    tableName = "overlay_layouts",
    primaryKeys = ["profileId", "orientation"],
    foreignKeys = [ForeignKey(
        entity = ProfileEntity::class,
        parentColumns = ["id"],
        childColumns = ["profileId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class OverlayLayoutEntity(
    val profileId: String,
    val orientation: String,
    val layoutJson: String
)

@Entity(tableName = "event_log", indices = [Index("timestampMillis")])
data class EventLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMillis: Long,
    val level: String,
    val category: String,
    val message: String,
    @ColumnInfo(name = "deviceName") val deviceName: String?,
    val latencyNanos: Long?
)
