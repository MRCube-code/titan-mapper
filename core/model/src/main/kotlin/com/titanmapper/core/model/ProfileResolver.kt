package com.titanmapper.core.model

/**
 * Automatic profile switching rule (pure, JVM-testable).
 *
 * Among enabled profiles matching the current (device, foreground package) pair, the most
 * specific wins: a package-specific profile beats a global one, a device-specific profile beats
 * an any-device one. Ties fall back to [MappingProfile.priority], then name for determinism.
 */
object ProfileResolver {
    fun resolve(
        profiles: List<MappingProfile>,
        deviceStableId: String?,
        foregroundPackage: String?
    ): MappingProfile? = profiles
        .asSequence()
        .filter { it.isActive }
        .filter { it.targetPackageName == null || it.targetPackageName == foregroundPackage }
        .filter { it.deviceFingerprintId == null || it.deviceFingerprintId == deviceStableId }
        .sortedWith(
            compareByDescending<MappingProfile> { specificity(it) }
                .thenByDescending { it.priority }
                .thenBy { it.name }
        )
        .firstOrNull()

    private fun specificity(p: MappingProfile): Int =
        (if (p.targetPackageName != null) 4 else 0) + (if (p.deviceFingerprintId != null) 2 else 0)
}
