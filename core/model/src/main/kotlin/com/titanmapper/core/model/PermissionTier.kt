package com.titanmapper.core.model

/**
 * The four capability tiers from Architecture §7. [level] orders them by raw capability
 * (higher = more capable) purely for display/sorting purposes — the engine does NOT
 * auto-select the highest available tier; the user explicitly picks a tier per profile
 * (see [MappingProfile.permissionTier]) and the runtime only ever *downgrades* automatically
 * if the chosen tier becomes unavailable mid-session (e.g. root revoked, Shizuku killed).
 */
enum class PermissionTier(val level: Int) {
    STANDARD(0),
    ACCESSIBILITY(1),
    SHIZUKU(2),
    ROOT(3);

    fun toCaptureTier(): CaptureTier = when (this) {
        STANDARD -> CaptureTier.STANDARD
        ACCESSIBILITY -> CaptureTier.ACCESSIBILITY
        SHIZUKU -> CaptureTier.SHIZUKU
        ROOT -> CaptureTier.ROOT
    }

    companion object {
        fun from(tier: CaptureTier): PermissionTier = when (tier) {
            CaptureTier.STANDARD -> STANDARD
            CaptureTier.ACCESSIBILITY -> ACCESSIBILITY
            CaptureTier.SHIZUKU -> SHIZUKU
            CaptureTier.ROOT -> ROOT
        }
    }
}

/**
 * Snapshot of which tiers are actually usable on this device right now, produced by
 * `:service/permission`'s `CapabilityProbe`. Pure data — the probe itself needs Android APIs
 * (checking Settings.Secure, pinging Shizuku's binder, querying libsu) and therefore lives in
 * `:service`, not here.
 */
data class CapabilitySnapshot(
    val standardAvailable: Boolean = true, // always true — every Android app can do this
    val accessibilityEnabled: Boolean = false,
    val shizukuAvailable: Boolean = false,
    val rootAvailable: Boolean = false
) {
    fun isAvailable(tier: PermissionTier): Boolean = when (tier) {
        PermissionTier.STANDARD -> standardAvailable
        PermissionTier.ACCESSIBILITY -> accessibilityEnabled
        PermissionTier.SHIZUKU -> shizukuAvailable
        PermissionTier.ROOT -> rootAvailable
    }

    /** Highest tier currently usable — used only for *display* ("Root available!") never for silent auto-upgrade. */
    fun highestAvailable(): PermissionTier =
        PermissionTier.entries.sortedByDescending { it.level }.first { isAvailable(it) }
}
