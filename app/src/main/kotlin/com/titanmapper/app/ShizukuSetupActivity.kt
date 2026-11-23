package com.titanmapper.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import com.titanmapper.app.databinding.ActivityShizukuSetupBinding
import com.titanmapper.app.databinding.RowTitanPermissionBinding
import com.titanmapper.service.api.CapabilityMonitor
import dagger.hilt.android.AndroidEntryPoint
import rikka.shizuku.Shizuku
import javax.inject.Inject

/**
 * "Shizuku" setup screen, matching the reference screenshot exactly: a 3-step checklist plus a
 * tutorial link. Plain XML/ViewBinding, like [PermissionsManagementActivity] -- see its KDoc for
 * why.
 *
 * Steps reflect real Shizuku state via the Shizuku SDK directly (the same calls
 * [com.titanmapper.service.permission.CapabilityProbe] already makes):
 *  - "Start Shizuku service"       -> [Shizuku.pingBinder] (the privileged process is reachable)
 *  - "Grant permission to use Shizuku" -> [Shizuku.checkSelfPermission]
 *  - "Tap to Activate"             -> [CapabilityMonitor]'s shizukuAvailable snapshot, i.e. both
 *    of the above together. There's no finer-grained "activated" signal anywhere else in this
 *    codebase to distinguish step 3 from step 2, so rather than fake a third independent check,
 *    step 3 honestly mirrors the combined ping+permission result.
 */
@AndroidEntryPoint
class ShizukuSetupActivity : ComponentActivity() {

    @Inject lateinit var capabilityMonitor: CapabilityMonitor

    private lateinit var binding: ActivityShizukuSetupBinding

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { _, _ -> refreshStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityShizukuSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.closeButton.setOnClickListener { finish() }

        configureStepRow(binding.stepServiceInclude, R.string.shizuku_step_service) { openShizukuApp() }
        configureStepRow(binding.stepPermissionInclude, R.string.shizuku_step_permission) { requestShizukuPermission() }
        configureStepRow(binding.stepActivateInclude, R.string.shizuku_step_activate) { openShizukuApp() }

        binding.tutorialLink.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/guide/setup/")))
        }

        Shizuku.addRequestPermissionResultListener(permissionResultListener)
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        capabilityMonitor.refresh()
        refreshStatus()
    }

    private fun configureStepRow(row: RowTitanPermissionBinding, labelRes: Int, onClick: () -> Unit) {
        row.rowLabel.text = getString(labelRes)
        row.rowHelpBadge.visibility = View.GONE
        row.rowActionButton.visibility = View.GONE
        row.root.setOnClickListener { onClick() }
    }

    private fun refreshStatus() {
        val pinged = isShizukuPinged()
        val granted = pinged && isShizukuPermissionGranted()
        setStepDone(binding.stepServiceInclude, pinged)
        setStepDone(binding.stepPermissionInclude, granted)
        setStepDone(binding.stepActivateInclude, granted)
    }

    private fun setStepDone(row: RowTitanPermissionBinding, done: Boolean) {
        row.rowStatusIcon.visibility = if (done) View.VISIBLE else View.INVISIBLE
    }

    private fun isShizukuPinged(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    private fun isShizukuPermissionGranted(): Boolean = try {
        Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    private fun requestShizukuPermission() {
        if (!isShizukuPinged()) {
            openShizukuApp()
            return
        }
        if (isShizukuPermissionGranted()) return
        Shizuku.requestPermission((0..Int.MAX_VALUE).random())
    }

    private fun openShizukuApp() {
        val launchIntent = packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
        if (launchIntent != null) {
            startActivity(launchIntent)
        } else {
            // Shizuku app isn't installed -- send the user to its Play Store / F-Droid listing
            // rather than silently doing nothing.
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/")))
        }
    }

    private companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    }
}
