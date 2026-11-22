package com.titanmapper.service.plugin

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import com.titanmapper.core.common.ApplicationScope
import com.titanmapper.service.api.PluginActionInfo
import com.titanmapper.service.api.PluginInfo
import com.titanmapper.service.api.PluginRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plugin architecture (Architecture §14). Discovers apps exposing a ContentProvider that answers
 * [PLUGIN_INTENT_ACTION], and talks to each one through `ContentResolver.call` — a deliberately
 * tiny, AIDL-free contract so plugin and host can't suffer interface-version skew. Every call
 * into a plugin is wrapped in `runCatching` + a timeout: a misbehaving plugin can only fail its
 * own action resolution, never the host's input pipeline (Architecture §14).
 *
 * `ContentResolver.call` is a plain blocking Binder transaction, not a suspend function, so simply
 * wrapping it in `withTimeoutOrNull { ... }` would NOT actually bound how long it can block: a
 * cancelled coroutine is only ever noticed at a suspension point, and a thread sitting inside a
 * synchronous call never reaches one, so a truly hung plugin provider would hold that
 * Dispatchers.IO thread (shared pool-wide with every other blocking IO call in the app, including
 * the settings/profile repositories the input pipeline reads from) for as long as the plugin
 * chooses, not 500ms. [runInterruptible] actually ties coroutine cancellation to interrupting the
 * underlying thread, which is what gives [CALL_TIMEOUT_MS] a real chance of freeing that thread
 * instead of only changing what value is eventually returned once the call happens to finish.
 */
@Singleton
class PluginRegistryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope
) : PluginRegistry {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("titan_plugins", Context.MODE_PRIVATE)

    private val _plugins = MutableStateFlow<List<PluginInfo>>(emptyList())
    override val plugins: StateFlow<List<PluginInfo>> get() = _plugins

    override fun refresh() {
        scope.launch(Dispatchers.IO) {
            val intent = Intent(PLUGIN_INTENT_ACTION)
            val providers = context.packageManager.queryIntentContentProviders(intent, 0)
            val discovered = providers.mapNotNull { resolveInfo ->
                val providerInfo = resolveInfo.providerInfo ?: return@mapNotNull null
                val packageName = providerInfo.packageName
                val label = resolveInfo.loadLabel(context.packageManager)?.toString() ?: packageName
                val actions = queryActions(providerInfo.authority)
                PluginInfo(
                    packageName = packageName,
                    label = label,
                    enabled = prefs.getBoolean(keyFor(packageName), false),
                    actions = actions
                )
            }
            _plugins.value = discovered
        }
    }

    override fun setEnabled(packageName: String, enabled: Boolean) {
        prefs.edit().putBoolean(keyFor(packageName), enabled).apply()
        _plugins.value = _plugins.value.map { if (it.packageName == packageName) it.copy(enabled = enabled) else it }
    }

    /** Only invoked when the plugin is user-enabled; see [PluginRegistry] contract in ServiceApi.kt. */
    suspend fun invoke(pluginPackage: String, actionId: String, argument: String): Boolean {
        val plugin = _plugins.value.firstOrNull { it.packageName == pluginPackage && it.enabled } ?: return false
        val authority = authorityFor(pluginPackage) ?: return false
        return withTimeoutOrNull(CALL_TIMEOUT_MS) {
            runCatching {
                runInterruptible(Dispatchers.IO) {
                    val args = Bundle().apply { putString("actionId", actionId); putString("argument", argument) }
                    context.contentResolver.call(authorityUri(authority), METHOD_INVOKE, null, args)
                        ?.getBoolean("success", false) ?: false
                }
            }.getOrDefault(false)
        } ?: false
    }

    private suspend fun queryActions(authority: String): List<PluginActionInfo> =
        withTimeoutOrNull(CALL_TIMEOUT_MS) {
            runCatching {
                runInterruptible(Dispatchers.IO) {
                    val result = context.contentResolver.call(authorityUri(authority), METHOD_LIST_ACTIONS, null, null)
                    val ids = result?.getStringArrayList("ids") ?: return@runInterruptible emptyList()
                    val labels = result.getStringArrayList("labels") ?: ids
                    ids.indices.map { i -> PluginActionInfo(ids[i], labels.getOrElse(i) { ids[i] }) }
                }
            }.getOrDefault(emptyList())
        } ?: emptyList()

    private fun authorityFor(packageName: String): String? =
        context.packageManager.queryIntentContentProviders(Intent(PLUGIN_INTENT_ACTION), 0)
            .firstOrNull { it.providerInfo?.packageName == packageName }?.providerInfo?.authority

    private fun authorityUri(authority: String) = android.net.Uri.parse("content://$authority")

    private fun keyFor(packageName: String) = "enabled_$packageName"

    companion object {
        const val PLUGIN_INTENT_ACTION = "com.titanmapper.action.PLUGIN_PROVIDER"
        private const val METHOD_LIST_ACTIONS = "list_actions"
        private const val METHOD_INVOKE = "invoke_action"
        private const val CALL_TIMEOUT_MS = 500L
    }
}
