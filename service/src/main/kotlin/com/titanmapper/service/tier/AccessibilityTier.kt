package com.titanmapper.service.tier

import android.accessibilityservice.AccessibilityService
import android.provider.Settings
import android.content.Context
import android.text.TextUtils
import com.titanmapper.core.model.CaptureTier
import com.titanmapper.core.model.MappedAction
import com.titanmapper.service.engine.DispatchPhase
import com.titanmapper.service.engine.OutputDispatchSink
import com.titanmapper.service.services.TitanAccessibilityService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Accessibility tier backend (Architecture §4.3). Capture happens inside
 * [TitanAccessibilityService] itself (the OS, not us, decides when that service exists), so
 * [start]/[stop] here only check the user has enabled it and mark the backend ready; there is
 * no "start" call that creates the service.
 */
@Singleton
class AccessibilityTier @Inject constructor(
    @ApplicationContext private val context: Context
) : TierBackend {

    override val tier = CaptureTier.ACCESSIBILITY

    override val sink = object : OutputDispatchSink {
        override val tier = CaptureTier.ACCESSIBILITY

        override fun dispatch(action: MappedAction, phase: DispatchPhase): Boolean {
            val service = TitanAccessibilityService.instance ?: return false
            return when (action) {
                is MappedAction.TouchTap ->
                    if (phase != DispatchPhase.UP) {
                        val (x, y) = resolve(action.normalizedX, action.normalizedY)
                        service.dispatchTap(x, y, action.durationMillis)
                    } else true // HOLD "up" phase for a tap binding is a no-op, not a failure
                is MappedAction.TouchDrag -> {
                    val (fx, fy) = resolve(action.fromX, action.fromY)
                    val (tx, ty) = resolve(action.toX, action.toY)
                    service.dispatchDrag(fx, fy, tx, ty, action.durationMillis)
                }
                is MappedAction.SystemAction -> service.dispatchGlobalAction(globalActionFor(action.type))
                is MappedAction.MacroRun -> true // handled by MacroExecutor, which calls back into this sink per step
                else -> false // KeyInjection / MouseInjection: not possible via dispatchGesture, honestly rejected
            }
        }

        override fun supports(action: MappedAction): Boolean = when (action) {
            is MappedAction.TouchTap, is MappedAction.TouchDrag,
            is MappedAction.SystemAction, is MappedAction.MacroRun -> true
            else -> false
        }

        /** Overlay/screen bounds aren't known at the sink level; normalized coords resolve against the display metrics. */
        private fun resolve(nx: Float, ny: Float): Pair<Float, Float> {
            val metrics = context.resources.displayMetrics
            return nx * metrics.widthPixels to ny * metrics.heightPixels
        }

        private fun globalActionFor(type: com.titanmapper.core.model.SystemActionType): Int = when (type) {
            com.titanmapper.core.model.SystemActionType.BACK -> AccessibilityService.GLOBAL_ACTION_BACK
            com.titanmapper.core.model.SystemActionType.HOME -> AccessibilityService.GLOBAL_ACTION_HOME
            com.titanmapper.core.model.SystemActionType.RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS
            com.titanmapper.core.model.SystemActionType.NOTIFICATIONS -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            com.titanmapper.core.model.SystemActionType.QUICK_SETTINGS -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            com.titanmapper.core.model.SystemActionType.SCREENSHOT -> AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT
            com.titanmapper.core.model.SystemActionType.LOCK_SCREEN -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
        }
    }

    override suspend fun start(): Boolean = isEnabledInSettings()

    override fun stop() = Unit // we never destroy the system-owned service ourselves

    override fun isHealthy(): Boolean = TitanAccessibilityService.instance != null && isEnabledInSettings()

    private fun isEnabledInSettings(): Boolean {
        val expected = "${context.packageName}/${TitanAccessibilityService::class.java.name}"
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':').apply { setString(enabled) }
        // SimpleStringSplitter implements both Iterator<String> and Iterable<String>, which makes
        // .asSequence()/.any{} ambiguous between the two stdlib extension overloads; iterate directly.
        while (splitter.hasNext()) {
            if (splitter.next().equals(expected, ignoreCase = true)) return true
        }
        return false
    }
}
