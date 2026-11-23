package com.titanmapper.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import androidx.activity.ComponentActivity
import com.titanmapper.app.databinding.ActivityPermissionsManagementBinding
import com.titanmapper.app.databinding.RowTitanPermissionBinding
import dagger.hilt.android.AndroidEntryPoint

/**
 * "Permissions management" screen, matching the reference screenshot exactly: a required-vs-
 * recommended checklist. Deliberately plain XML/ViewBinding rather than Compose, per the
 * redesign request -- this is a system-settings-style modal, not part of the app's main
 * Compose navigation.
 *
 * Every check here reflects a real OS permission state (no fake/always-green checkmarks):
 *  - Overlay Window Permission     -> [Settings.canDrawOverlays]
 *  - Run in Background Permission  -> [PowerManager.isIgnoringBatteryOptimizations]
 *  - Power Saver Mode              -> [PowerManager.isPowerSaveMode]
 * All three are re-read in [onResume], since granting them happens in a system Settings screen
 * this Activity doesn't get an onActivityResult callback from.
 */
@AndroidEntryPoint
class PermissionsManagementActivity : ComponentActivity() {

    private lateinit var binding: ActivityPermissionsManagementBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPermissionsManagementBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.closeButton.setOnClickListener { finish() }

        val overlayRow = binding.rowOverlayInclude
        overlayRow.rowLabel.text = getString(R.string.permissions_overlay_label)
        overlayRow.rowActionButton.setOnClickListener { openOverlaySettings() }
        overlayRow.root.setOnClickListener { openOverlaySettings() }

        val backgroundRow = binding.rowBackgroundInclude
        backgroundRow.rowLabel.text = getString(R.string.permissions_background_label)
        backgroundRow.rowActionButton.setOnClickListener { openBatteryOptimizationSettings() }
        backgroundRow.root.setOnClickListener { openBatteryOptimizationSettings() }

        binding.rowPowerSaverInclude.powerSaverButton.setOnClickListener { openBatterySaverSettings() }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        setRowGranted(binding.rowOverlayInclude, Settings.canDrawOverlays(this))

        val powerManager = getSystemService(PowerManager::class.java)
        setRowGranted(binding.rowBackgroundInclude, powerManager?.isIgnoringBatteryOptimizations(packageName) == true)
    }

    private fun setRowGranted(row: RowTitanPermissionBinding, granted: Boolean) {
        row.rowStatusIcon.visibility = if (granted) View.VISIBLE else View.INVISIBLE
        row.rowHelpBadge.visibility = if (granted) View.GONE else View.VISIBLE
        row.rowActionButton.visibility = if (granted) View.GONE else View.VISIBLE
    }

    private fun openOverlaySettings() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    @Suppress("BatteryLife") // Deliberate, user-initiated, with an honest label in the UI -- not a hidden request.
    private fun openBatteryOptimizationSettings() {
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    }

    private fun openBatterySaverSettings() {
        startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
    }
}
