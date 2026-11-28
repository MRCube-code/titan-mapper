package com.titanmapper.feature.mapping

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.titanmapper.core.model.ActionMode
import com.titanmapper.core.model.Binding
import com.titanmapper.core.model.InputTrigger
import com.titanmapper.core.model.KeyAction
import com.titanmapper.core.model.MacroDefinition
import com.titanmapper.core.model.MappedAction
import com.titanmapper.core.model.ModifierKey
import com.titanmapper.core.model.MouseButton
import com.titanmapper.core.model.ScrollDirection
import com.titanmapper.core.model.SystemActionType
import com.titanmapper.core.ui.components.LabeledSlider
import com.titanmapper.core.ui.components.StatusChip
import com.titanmapper.core.ui.components.StatusKind
import com.titanmapper.core.ui.format.describe
import com.titanmapper.core.ui.format.displayName
import com.titanmapper.core.ui.theme.TitanSpacing
import com.titanmapper.service.api.PluginInfo

private enum class TriggerKind { KEY, COMBO, MOUSE, SCROLL }

/** Phone hardware keys an app can't receive as typed key events, so they're picked from chips. */
private val PHONE_OUTPUT_KEYS = listOf(
    com.titanmapper.core.model.KeyCode.POWER, com.titanmapper.core.model.KeyCode.VOLUME_UP,
    com.titanmapper.core.model.KeyCode.VOLUME_DOWN, com.titanmapper.core.model.KeyCode.VOLUME_MUTE,
    com.titanmapper.core.model.KeyCode.NAV_HOME, com.titanmapper.core.model.KeyCode.NAV_BACK,
    com.titanmapper.core.model.KeyCode.APP_SWITCH, com.titanmapper.core.model.KeyCode.CAMERA,
    com.titanmapper.core.model.KeyCode.BRIGHTNESS_UP, com.titanmapper.core.model.KeyCode.BRIGHTNESS_DOWN
)
private enum class ActionKind { TOUCH_TAP, TOUCH_DRAG, KEY_INJECT, MOUSE_INJECT, SYSTEM, APP_LAUNCH, MACRO, PLUGIN, NONE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BindingEditorSheet(
    existing: Binding?,
    tierCapability: TierCapability,
    macros: List<MacroDefinition>,
    plugins: List<PluginInfo>,
    inputEvents: kotlinx.coroutines.flow.Flow<com.titanmapper.core.model.RawInputEvent>,
    newId: () -> String,
    conflictCheck: (InputTrigger) -> String?,
    onSave: (Binding) -> Unit,
    onDismiss: () -> Unit
) {
    var triggerKind by remember { mutableStateOf(triggerKindOf(existing?.trigger)) }
    var capturedKey by remember { mutableStateOf((existing?.trigger as? InputTrigger.SingleKey)?.key ?: (existing?.trigger as? InputTrigger.Combo)?.key) }
    var modifiers by remember { mutableStateOf((existing?.trigger as? InputTrigger.Combo)?.modifiers ?: emptySet()) }
    var mouseButton by remember { mutableStateOf((existing?.trigger as? InputTrigger.Mouse)?.button ?: MouseButton.LEFT) }
    var scrollDir by remember { mutableStateOf((existing?.trigger as? InputTrigger.Scroll)?.direction ?: ScrollDirection.UP) }

    var actionKind by remember { mutableStateOf(actionKindOf(existing?.action)) }
    var tapX by remember { mutableFloatStateOf((existing?.action as? MappedAction.TouchTap)?.normalizedX ?: 0.5f) }
    var tapY by remember { mutableFloatStateOf((existing?.action as? MappedAction.TouchTap)?.normalizedY ?: 0.5f) }
    var injectKey by remember { mutableStateOf((existing?.action as? MappedAction.KeyInjection)?.key ?: com.titanmapper.core.model.KeyCode.A) }
    // Separate from [mouseButton] (the TRIGGER's button): they used to share one variable, so
    // picking the output button silently changed the trigger too and "M4 -> Left click" was
    // impossible to create.
    var outMouseButton by remember { mutableStateOf((existing?.action as? MappedAction.MouseInjection.ButtonPress)?.button ?: MouseButton.LEFT) }
    var systemType by remember { mutableStateOf((existing?.action as? MappedAction.SystemAction)?.type ?: SystemActionType.BACK) }
    var appPackage by remember { mutableStateOf((existing?.action as? MappedAction.AppLaunch)?.packageName ?: "") }
    var macroId by remember { mutableStateOf((existing?.action as? MappedAction.MacroRun)?.macroId ?: macros.firstOrNull()?.id ?: "") }
    var pluginChoice by remember { mutableStateOf(existing?.action as? MappedAction.PluginAction) }

    var mode by remember { mutableStateOf(existing?.mode ?: ActionMode.TAP) }
    var repeatMs by remember { mutableFloatStateOf((existing?.repeatIntervalMillis ?: 50L).toFloat()) }

    // A full-screen panel inside the Activity window, NOT a ModalBottomSheet: a bottom sheet is a
    // separate Dialog window, so key and mouse events never reached MainActivity (and therefore
    // never reached the input bridge / live event stream) while it was open. That is why
    // Diagnostics saw every click and key but the editor's "press a key" box saw nothing.
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())
                .padding(TitanSpacing.l).padding(bottom = TitanSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(TitanSpacing.m)
        ) {
            Text(if (existing == null) "New binding" else "Edit binding", style = MaterialTheme.typography.titleLarge)

            Text("Trigger", style = MaterialTheme.typography.titleSmall)
            Row {
                TriggerKind.entries.forEach { kind ->
                    FilterChip(selected = triggerKind == kind, onClick = { triggerKind = kind }, label = { Text(kind.name) }, modifier = Modifier.padding(end = TitanSpacing.xs))
                }
            }
            when (triggerKind) {
                TriggerKind.KEY -> PressToCaptureBox(
                    label = capturedKey?.displayName() ?: "Tap here, then press a key or mouse button",
                    events = inputEvents,
                    onKey = { capturedKey = it },
                    onButton = { mouseButton = it; triggerKind = TriggerKind.MOUSE }
                )
                TriggerKind.COMBO -> {
                    Row { ModifierKey.entries.forEach { m ->
                        FilterChip(
                            selected = m in modifiers, onClick = { modifiers = if (m in modifiers) modifiers - m else modifiers + m },
                            label = { Text(m.name) }, modifier = Modifier.padding(end = TitanSpacing.xs)
                        )
                    } }
                    PressToCaptureBox(
                        label = capturedKey?.displayName() ?: "Tap here, then press the final key",
                        events = inputEvents,
                        onKey = { capturedKey = it },
                        onButton = { }
                    )
                }
                TriggerKind.MOUSE -> Row { MouseButton.entries.forEach { b ->
                    FilterChip(selected = mouseButton == b, onClick = { mouseButton = b }, label = { Text(b.displayName()) }, modifier = Modifier.padding(end = TitanSpacing.xs))
                } }
                TriggerKind.SCROLL -> Row { ScrollDirection.entries.forEach { d ->
                    FilterChip(selected = scrollDir == d, onClick = { scrollDir = d }, label = { Text(d.name) }, modifier = Modifier.padding(end = TitanSpacing.xs))
                } }
            }

            Text("Mode", style = MaterialTheme.typography.titleSmall)
            Row { ActionMode.entries.forEach { m ->
                FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(m.displayName()) }, modifier = Modifier.padding(end = TitanSpacing.xs))
            } }
            if (mode == ActionMode.REPEAT) {
                LabeledSlider("Repeat interval", repeatMs, { repeatMs = it }, 10f..1000f, "${repeatMs.toInt()} ms")
            }

            Text("Action", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(TitanSpacing.xs)) {
                ActionKind.entries.forEach { kind ->
                    FilterChip(selected = actionKind == kind, onClick = { actionKind = kind }, label = { Text(kind.name.replace('_', ' ')) })
                }
            }
            when (actionKind) {
                ActionKind.TOUCH_TAP -> TapSurface(tapX, tapY) { x, y -> tapX = x; tapY = y }
                ActionKind.TOUCH_DRAG -> Text("Drag endpoints default to center-to-center; fine-tune in the overlay editor.", style = MaterialTheme.typography.bodySmall)
                ActionKind.KEY_INJECT -> Column(verticalArrangement = Arrangement.spacedBy(TitanSpacing.xs)) {
                    PressToCaptureBox(
                        label = injectKey.displayName() + " (tap, then press a key or mouse button to change)",
                        events = inputEvents,
                        onKey = { injectKey = it },
                        onButton = { outMouseButton = it; actionKind = ActionKind.MOUSE_INJECT }
                    )
                    Text("Or pick a phone key (can't be typed):", style = MaterialTheme.typography.bodySmall)
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState())) { PHONE_OUTPUT_KEYS.forEach { k ->
                        FilterChip(selected = injectKey == k, onClick = { injectKey = k }, label = { Text(k.displayName()) }, modifier = Modifier.padding(end = TitanSpacing.xs))
                    } }
                }
                ActionKind.MOUSE_INJECT -> Row(modifier = Modifier.horizontalScroll(rememberScrollState())) { MouseButton.entries.forEach { b ->
                    FilterChip(selected = outMouseButton == b, onClick = { outMouseButton = b }, label = { Text(b.displayName()) }, modifier = Modifier.padding(end = TitanSpacing.xs))
                } }
                ActionKind.SYSTEM -> Row { SystemActionType.entries.forEach { t ->
                    FilterChip(selected = systemType == t, onClick = { systemType = t }, label = { Text(t.name) }, modifier = Modifier.padding(end = TitanSpacing.xs))
                } }
                ActionKind.APP_LAUNCH -> OutlinedTextField(value = appPackage, onValueChange = { appPackage = it }, label = { Text("Package name") }, singleLine = true)
                ActionKind.MACRO -> if (macros.isEmpty()) Text("No macros yet. Create one from the Macros tab.") else Row {
                    macros.forEach { m -> FilterChip(selected = macroId == m.id, onClick = { macroId = m.id }, label = { Text(m.name) }, modifier = Modifier.padding(end = TitanSpacing.xs)) }
                }
                ActionKind.PLUGIN -> {
                    val enabledPlugins = plugins.filter { it.enabled }
                    if (enabledPlugins.isEmpty()) Text("No enabled plugins. Enable one in Settings.") else {
                        enabledPlugins.forEach { p -> p.actions.forEach { a ->
                            FilterChip(
                                selected = pluginChoice?.pluginPackage == p.packageName && pluginChoice?.actionId == a.id,
                                onClick = { pluginChoice = MappedAction.PluginAction(p.packageName, a.id) },
                                label = { Text("${p.label}: ${a.label}") },
                                modifier = Modifier.padding(end = TitanSpacing.xs)
                            )
                        } }
                    }
                }
                ActionKind.NONE -> Text("No action — disables this trigger without deleting it.", style = MaterialTheme.typography.bodySmall)
            }

            val action = buildAction(actionKind, tapX, tapY, injectKey, outMouseButton, systemType, appPackage, macroId, pluginChoice)
            if (action != null && !tierCapability.supports(action)) {
                StatusChip(text = "This profile's tier can't perform this action", kind = StatusKind.WARN)
            }

            val trigger = buildTrigger(triggerKind, capturedKey, modifiers, mouseButton, scrollDir)
            val conflict = trigger?.let(conflictCheck)
            if (conflict != null) StatusChip(text = conflict, kind = StatusKind.WARN)

            Row(horizontalArrangement = Arrangement.spacedBy(TitanSpacing.s)) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                TextButton(
                    enabled = trigger != null && action != null,
                    onClick = {
                        if (trigger != null && action != null) {
                            onSave(
                                Binding(
                                    id = existing?.id ?: newId(),
                                    trigger = trigger,
                                    action = action,
                                    mode = mode,
                                    repeatIntervalMillis = repeatMs.toLong(),
                                    isEnabled = existing?.isEnabled ?: true
                                )
                            )
                        }
                    }
                ) { Text("Save") }
            }
        }
    }
}

@Composable
private fun TapSurface(x: Float, y: Float, onPick: (Float, Float) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .pointerInput(Unit) {
                detectTapGestures { offset -> onPick(offset.x / size.width, offset.y / size.height) }
            }
    ) {
        Box(
            modifier = Modifier
                .padding(start = (x * 300).dp, top = (y * 169).dp)
                .height(12.dp)
                .aspectRatio(1f)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(MaterialTheme.colorScheme.primary)
        )
    }
}

private fun triggerKindOf(trigger: InputTrigger?): TriggerKind = when (trigger) {
    is InputTrigger.Combo -> TriggerKind.COMBO
    is InputTrigger.Mouse -> TriggerKind.MOUSE
    is InputTrigger.Scroll -> TriggerKind.SCROLL
    else -> TriggerKind.KEY
}

private fun actionKindOf(action: MappedAction?): ActionKind = when (action) {
    is MappedAction.TouchTap -> ActionKind.TOUCH_TAP
    is MappedAction.TouchDrag -> ActionKind.TOUCH_DRAG
    is MappedAction.KeyInjection -> ActionKind.KEY_INJECT
    is MappedAction.MouseInjection -> ActionKind.MOUSE_INJECT
    is MappedAction.SystemAction -> ActionKind.SYSTEM
    is MappedAction.AppLaunch -> ActionKind.APP_LAUNCH
    is MappedAction.MacroRun -> ActionKind.MACRO
    is MappedAction.PluginAction -> ActionKind.PLUGIN
    else -> ActionKind.TOUCH_TAP
}

private fun buildTrigger(
    kind: TriggerKind, key: com.titanmapper.core.model.KeyCode?, modifiers: Set<ModifierKey>,
    button: MouseButton, scroll: ScrollDirection
): InputTrigger? = when (kind) {
    TriggerKind.KEY -> key?.let { InputTrigger.SingleKey(it) }
    TriggerKind.COMBO -> key?.let { InputTrigger.Combo(modifiers, it) }
    TriggerKind.MOUSE -> InputTrigger.Mouse(button)
    TriggerKind.SCROLL -> InputTrigger.Scroll(scroll)
}

private fun buildAction(
    kind: ActionKind, x: Float, y: Float, key: com.titanmapper.core.model.KeyCode, button: MouseButton,
    system: SystemActionType, appPackage: String, macroId: String, plugin: MappedAction.PluginAction?
): MappedAction? = when (kind) {
    ActionKind.TOUCH_TAP -> MappedAction.TouchTap(x, y)
    ActionKind.TOUCH_DRAG -> MappedAction.TouchDrag(0.3f, 0.5f, 0.7f, 0.5f, 120)
    ActionKind.KEY_INJECT -> MappedAction.KeyInjection(key, KeyAction.DOWN)
    ActionKind.MOUSE_INJECT -> MappedAction.MouseInjection.ButtonPress(button, KeyAction.DOWN)
    ActionKind.SYSTEM -> MappedAction.SystemAction(system)
    ActionKind.APP_LAUNCH -> appPackage.takeIf { it.isNotBlank() }?.let { MappedAction.AppLaunch(it) }
    ActionKind.MACRO -> macroId.takeIf { it.isNotBlank() }?.let { MappedAction.MacroRun(it) }
    ActionKind.PLUGIN -> plugin
    ActionKind.NONE -> MappedAction.NoAction
}
