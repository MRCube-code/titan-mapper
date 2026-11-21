package com.titanmapper.core.ui.format

import com.titanmapper.core.model.ActionMode
import com.titanmapper.core.model.CaptureTier
import com.titanmapper.core.model.InputTrigger
import com.titanmapper.core.model.KeyAction
import com.titanmapper.core.model.KeyCode
import com.titanmapper.core.model.MappedAction
import com.titanmapper.core.model.ModifierKey
import com.titanmapper.core.model.MouseButton
import com.titanmapper.core.model.PermissionTier
import com.titanmapper.core.model.ScrollDirection
import com.titanmapper.core.model.SystemActionType
import java.util.Locale
import kotlin.math.roundToInt

private fun prettify(name: String): String =
    name.lowercase(Locale.ROOT).split('_').joinToString(" ") { part ->
        part.replaceFirstChar { it.titlecase(Locale.ROOT) }
    }

fun KeyCode.displayName(): String = when (this) {
    KeyCode.MINUS -> "-"
    KeyCode.EQUALS -> "="
    KeyCode.LEFT_BRACKET -> "["
    KeyCode.RIGHT_BRACKET -> "]"
    KeyCode.BACKSLASH -> "\\"
    KeyCode.SEMICOLON -> ";"
    KeyCode.APOSTROPHE -> "'"
    KeyCode.GRAVE -> "`"
    KeyCode.COMMA -> ","
    KeyCode.PERIOD -> "."
    KeyCode.SLASH -> "/"
    KeyCode.NUMPAD_DIVIDE -> "Num /"
    KeyCode.NUMPAD_MULTIPLY -> "Num *"
    KeyCode.NUMPAD_SUBTRACT -> "Num -"
    KeyCode.NUMPAD_ADD -> "Num +"
    KeyCode.NUMPAD_ENTER -> "Num Enter"
    KeyCode.NUMPAD_DECIMAL -> "Num ."
    KeyCode.PAUSE_BREAK -> "Pause"
    KeyCode.UNKNOWN -> "Unknown key"
    else -> when {
        name.startsWith("DIGIT_") -> name.removePrefix("DIGIT_")
        name.startsWith("NUMPAD_") -> "Num " + name.removePrefix("NUMPAD_")
        else -> prettify(name)
    }
}

fun MouseButton.displayName(): String = when (this) {
    MouseButton.LEFT -> "Left"
    MouseButton.RIGHT -> "Right"
    MouseButton.MIDDLE -> "Middle"
    MouseButton.BACK -> "Back"
    MouseButton.FORWARD -> "Forward"
    MouseButton.EXTRA_1 -> "Extra 1"
    MouseButton.EXTRA_2 -> "Extra 2"
}

private fun ModifierKey.label(): String = when (this) {
    ModifierKey.CTRL -> "Ctrl"
    ModifierKey.ALT -> "Alt"
    ModifierKey.SHIFT -> "Shift"
    ModifierKey.META -> "Meta"
}

private val MODIFIER_ORDER = listOf(ModifierKey.CTRL, ModifierKey.ALT, ModifierKey.SHIFT, ModifierKey.META)

private fun ScrollDirection.label(): String = prettify(name)

fun InputTrigger.describe(): String = when (this) {
    is InputTrigger.SingleKey -> key.displayName()
    is InputTrigger.Combo ->
        (MODIFIER_ORDER.filter { it in modifiers }.map { it.label() } + key.displayName()).joinToString(" + ")
    is InputTrigger.Sequence -> keys.joinToString(" then ") { it.displayName() }
    is InputTrigger.Mouse -> "Mouse ${button.displayName()}"
    is InputTrigger.Scroll -> "Scroll ${direction.label()}"
}

private fun percent(v: Float): String = "${(v * 100).roundToInt()}%"

private fun SystemActionType.label(): String = when (this) {
    SystemActionType.BACK -> "Back"
    SystemActionType.HOME -> "Home"
    SystemActionType.RECENTS -> "Recent apps"
    SystemActionType.NOTIFICATIONS -> "Notifications"
    SystemActionType.QUICK_SETTINGS -> "Quick settings"
    SystemActionType.SCREENSHOT -> "Screenshot"
    SystemActionType.LOCK_SCREEN -> "Lock screen"
}

private fun KeyAction.label(): String = if (this == KeyAction.DOWN) "Press" else "Release"

fun MappedAction.describe(): String = when (this) {
    is MappedAction.TouchTap -> "Tap at ${percent(normalizedX)}, ${percent(normalizedY)}"
    is MappedAction.TouchDrag ->
        "Drag ${percent(fromX)}, ${percent(fromY)} to ${percent(toX)}, ${percent(toY)}"
    is MappedAction.KeyInjection -> "${action.label()} key ${key.displayName()}"
    is MappedAction.MouseInjection.Move -> "Move mouse by ${deltaX.roundToInt()}, ${deltaY.roundToInt()}"
    is MappedAction.MouseInjection.ButtonPress -> "${action.label()} mouse ${button.displayName()}"
    is MappedAction.SystemAction -> "System: ${type.label()}"
    is MappedAction.AppLaunch -> "Launch $packageName"
    is MappedAction.MacroRun -> "Run macro"
    is MappedAction.PluginAction -> "Plugin $pluginPackage: $actionId"
    MappedAction.NoAction -> "Do nothing"
}

fun ActionMode.displayName(): String = when (this) {
    ActionMode.HOLD -> "Hold"
    ActionMode.TOGGLE -> "Toggle"
    ActionMode.TAP -> "Tap"
    ActionMode.REPEAT -> "Repeat"
}

fun PermissionTier.displayName(): String = when (this) {
    PermissionTier.STANDARD -> "Standard"
    PermissionTier.ACCESSIBILITY -> "Accessibility"
    PermissionTier.SHIZUKU -> "Shizuku"
    PermissionTier.ROOT -> "Root"
}

fun CaptureTier.displayName(): String = when (this) {
    CaptureTier.STANDARD -> "Standard"
    CaptureTier.ACCESSIBILITY -> "Accessibility"
    CaptureTier.SHIZUKU -> "Shizuku"
    CaptureTier.ROOT -> "Root"
}

/** Plain-language capability/limit summary shown wherever a tier can be chosen. */
fun PermissionTier.summary(): String = when (this) {
    PermissionTier.STANDARD ->
        "Works only while Titan Mapper is in front. Good for configuring and testing; it cannot remap other apps."
    PermissionTier.ACCESSIBILITY ->
        "Intercepts keys system-wide and performs touch gestures. It cannot inject real key or mouse events into other apps, and gestures add about a frame of latency."
    PermissionTier.SHIZUKU ->
        "Injects real key and mouse events system-wide without root. Needs the Shizuku app running; most devices need it re-activated after a reboot."
    PermissionTier.ROOT ->
        "Reads input devices directly and injects events system-wide. Needs a rooted device and a grant from your root manager."
}

/** "850 µs" or "1.2 ms". */
fun formatMicros(micros: Long): String =
    if (micros >= 1_000) String.format(Locale.US, "%.1f ms", micros / 1000.0) else "$micros µs"

/** "2h 05m", "4m 12s" or "9s". */
fun formatDuration(millis: Long): String {
    val total = (millis / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return when {
        h > 0 -> String.format(Locale.US, "%dh %02dm", h, m)
        m > 0 -> String.format(Locale.US, "%dm %02ds", m, s)
        else -> "${s}s"
    }
}
