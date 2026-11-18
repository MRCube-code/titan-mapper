package com.titanmapper.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for [ProfileResolver] specificity logic.
 * Specificity ranking: device+package > device-only > package-only > wildcard.
 */
class ProfileResolverTest {

    private fun profile(
        id: String,
        priority: Int = 0,
        deviceId: String? = null,
        packageName: String? = null,
        active: Boolean = true
    ) = MappingProfile(
        id = id,
        name = id,
        isActive = active,
        permissionTier = CaptureTier.STANDARD,
        priority = priority,
        deviceFingerprintId = deviceId,
        targetPackageName = packageName
    )

    @Test
    fun `most specific profile wins device+package over package-only`() {
        val profiles = listOf(
            profile("pkg-only", packageName = "com.game"),
            profile("both", deviceId = "device1", packageName = "com.game")
        )
        val result = ProfileResolver.resolve(
            profiles = profiles,
            deviceStableId = "device1",
            foregroundPackage = "com.game"
        )
        assertEquals("both", result?.id)
    }

    @Test
    fun `device-only profile wins over wildcard`() {
        val profiles = listOf(
            profile("wildcard"),
            profile("device", deviceId = "device1")
        )
        val result = ProfileResolver.resolve(
            profiles = profiles,
            deviceStableId = "device1",
            foregroundPackage = null
        )
        assertEquals("device", result?.id)
    }

    @Test
    fun `inactive profiles are excluded`() {
        val profiles = listOf(
            profile("inactive", deviceId = "device1", active = false),
            profile("wildcard")
        )
        val result = ProfileResolver.resolve(
            profiles = profiles,
            deviceStableId = "device1",
            foregroundPackage = null
        )
        assertEquals("wildcard", result?.id)
    }

    @Test
    fun `returns null when no profiles match`() {
        val result = ProfileResolver.resolve(
            profiles = emptyList(),
            deviceStableId = "device1",
            foregroundPackage = "com.game"
        )
        assertNull(result)
    }

    @Test
    fun `higher priority wins among same specificity`() {
        val profiles = listOf(
            profile("low", priority = 0, packageName = "com.game"),
            profile("high", priority = 10, packageName = "com.game")
        )
        val result = ProfileResolver.resolve(
            profiles = profiles,
            deviceStableId = null,
            foregroundPackage = "com.game"
        )
        assertEquals("high", result?.id)
    }

    @Test
    fun `profile bound to a different device does not match`() {
        val profiles = listOf(profile("other-device", deviceId = "device2"))
        val result = ProfileResolver.resolve(
            profiles = profiles,
            deviceStableId = "device1",
            foregroundPackage = null
        )
        assertNull(result)
    }
}
