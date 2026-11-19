package com.titanmapper.service.permission

import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import com.titanmapper.core.model.CapabilitySnapshot
import com.titanmapper.service.api.CapabilityMonitor
import com.titanmapper.service.services.TitanAccessibilityService
import com.topjohnwu.superuser.Shell
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Probes which permission tiers are usable right now (Architecture §7). The result is for
 * *display* ("Root available!") and for automatic downgrade — never for silent auto-upgrade;
 * the active tier is always the one the user explicitly chose.
 */
@Singleton
class CapabilityProbe @Inject constructor(
    @ApplicationContext private val context: Context
) : CapabilityMonitor {

    private val scope = CoroutineScope(Dispatchers.Default)
    private val _snapshot = MutableStateFlow(CapabilitySnapshot())
    override val snapshot: StateFlow<CapabilitySnapshot> get() = _snapshot

    init {
        refresh()
    }

    override fun refresh() {
        scope.launch {
            val accessibility = isAccessibilityEnabled()
            val shizuku = isShizukuAvailable()
            val root = withContext(Dispatchers.IO) { isRootAvailable() }
            _snapshot.value = CapabilitySnapshot(
                standardAvailable = true,
                accessibilityEnabled = accessibility,
                shizukuAvailable = shizuku,
                rootAvailable = root
            )
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expected = "${context.packageName}/${TitanAccessibilityService::class.java.name}"
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':').apply { setString(enabled) }
        return splitter.asSequence().any { it.equals(expected, ignoreCase = true) }
    }

    private fun isShizukuAvailable(): Boolean = try {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false // Shizuku app not installed / not running — not an error state, just unavailable
    }

    /**
     * [Shell.isAppGrantedRoot] returns null while the grant dialog hasn't been answered yet; we
     * treat "unknown" as "not available" for display purposes rather than prompting here — the
     * actual `su` request happens lazily when the user picks Root mode, inside [com.titanmapper.service.tier.RootTier].
     */
    private fun isRootAvailable(): Boolean = Shell.isAppGrantedRoot() == true
}
