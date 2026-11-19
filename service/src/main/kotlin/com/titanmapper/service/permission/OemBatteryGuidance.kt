package com.titanmapper.service.permission

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Aggressive OEM battery managers (MIUI, OneUI, ColorOS, etc.) kill long-lived Accessibility
 * services unless the app is whitelisted outside of stock Android Settings. This offers the
 * OEM-specific screen when recognized, falling back to the generic app-details page — it never
 * silently fails to open something.
 */
object OemBatteryGuidance {
    private val OEM_INTENTS: Map<String, (String) -> Intent> = mapOf(
        "xiaomi" to { pkg -> Intent().setClassName(
            "com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"
        ) },
        "oppo" to { pkg -> Intent().setClassName(
            "com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"
        ) },
        "vivo" to { pkg -> Intent().setClassName(
            "com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
        ) },
        "huawei" to { pkg -> Intent().setClassName(
            "com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
        ) }
    )

    fun open(context: Context) {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val oemIntent = OEM_INTENTS.entries.firstOrNull { manufacturer.contains(it.key) }?.value?.invoke(context.packageName)
        if (oemIntent != null) {
            try {
                context.startActivity(oemIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: ActivityNotFoundException) {
                // Fall through to the generic settings page below.
            }
        }
        val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(fallback)
    }
}
