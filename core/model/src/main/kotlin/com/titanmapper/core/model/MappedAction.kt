package com.titanmapper.core.model

import kotlinx.serialization.Serializable

/**
 * What happens when a binding resolves. This is the output of the MappingEngine and the input
 * to whichever `OutputDispatchSink` the active [PermissionTier] provides (Architecture §4.6).
 *
 * Kept as data describing *intent* ("tap at this normalized overlay coordinate", "inject this
 * key"), never as platform calls themselves — the sealed hierarchy is what gets persisted in
 * [BindingEntity.actionJson] and shared via `.titanprofile`/macro exports, so it must stay
 * Android-API-free to remain forward-compatible across OS versions and tiers.
 */
@Serializable
sealed interface MappedAction {

    /**
     * Tap a point on a normalized [0f,1f] x [0f,1f] canvas (resolution-independent — resolved
     * against the current screen/overlay bounds at dispatch time). Used for virtual-button
     * style remaps (e.g. "W key -> tap this spot on the touch-based movement joystick").
     */
    @Serializable
    data class TouchTap(val normalizedX: Float, val normalizedY: Float, val durationMillis: Long = 40L) : MappedAction

    /** Drag from one normalized point to another — e.g. mouse movement mapped onto a virtual joystick drag. */
    @Serializable
    data class TouchDrag(
        val fromX: Float, val fromY: Float,
        val toX: Float, val toY: Float,
        val durationMillis: Long
    ) : MappedAction

    /** Inject a synthetic key event system-wide. Only meaningful on Shizuku/Root tiers (Architecture §4.4-4.5). */
    @Serializable
    data class KeyInjection(val key: KeyCode, val action: KeyAction) : MappedAction

    /** Inject a synthetic relative mouse-motion or button event. Shizuku/Root tiers only. */
    @Serializable
    sealed interface MouseInjection : MappedAction {
        @Serializable
        data class Move(val deltaX: Float, val deltaY: Float) : MouseInjection
        @Serializable
        data class ButtonPress(val button: MouseButton, val action: KeyAction) : MouseInjection
    }

    /** OS-level action that doesn't need raw injection — back/home/recents/notification-pull-down. */
    @Serializable
    data class SystemAction(val type: SystemActionType) : MappedAction

    /** Launch another app by package name (e.g. a macro step that opens a specific game). */
    @Serializable
    data class AppLaunch(val packageName: String) : MappedAction

    /** Run a named macro (resolved against [com.titanmapper.core.model.MacroDefinition] by id). */
    @Serializable
    data class MacroRun(val macroId: String) : MappedAction

    /**
     * Invoke an action exposed by an installed, user-enabled plugin app (Architecture §14).
     * [argument] is opaque to Titan Mapper and passed to the plugin as-is.
     */
    @Serializable
    data class PluginAction(val pluginPackage: String, val actionId: String, val argument: String = "") : MappedAction

    /** No-op placeholder explicitly chosen by the user to "unbind" a key without deleting the binding row. */
    @Serializable
    data object NoAction : MappedAction
}

@Serializable
enum class SystemActionType { BACK, HOME, RECENTS, NOTIFICATIONS, QUICK_SETTINGS, SCREENSHOT, LOCK_SCREEN }
