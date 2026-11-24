package com.titanmapper.core.model

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Appearance configuration consumed by the theme engine in :core:ui. */
data class ThemeConfig(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    /** Material You wallpaper colors on Android 12+; ignored when [accent] is chosen explicitly. */
    val useDynamicColor: Boolean = true,
    val accent: ProfileColor = ProfileColor.BLUE,
    /** Tighter spacing for pointer-driven use; auto-enabled when a mouse is detected if true. */
    val compactWhenPointerPresent: Boolean = true
)

data class AppSettings(
    val theme: ThemeConfig = ThemeConfig(),
    val preferredTier: PermissionTier = PermissionTier.STANDARD,
    val autoProfileSwitch: Boolean = true,
    val eventLogCapacity: Int = 5_000,
    val latencyMonitorEnabled: Boolean = false,
    val idleTeardownMinutes: Int = 10,
    val startServiceOnBoot: Boolean = false,
    /** Shows the real floating radial keymap overlay (OverlayMenuManager, :service) while the
     *  input service is running. Still gated at runtime by Settings.canDrawOverlays(). */
    val showOverlayMenu: Boolean = false,
    val updateChannelUrl: String = ""
) {
    companion object {
        const val MIN_LOG_CAPACITY = 500
        const val MAX_LOG_CAPACITY = 50_000
    }
}
