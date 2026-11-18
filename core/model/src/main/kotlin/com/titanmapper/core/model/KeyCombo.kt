package com.titanmapper.core.model

import kotlinx.serialization.Serializable

/**
 * A trigger that a [BindingEntity]-backed binding matches against incoming [RawInputEvent]s.
 * Supports single keys, modifier combinations ("Ctrl+Shift+A"), mouse buttons, and scroll
 * ticks — anything a user can bind an action to.
 *
 * `@Serializable` so it round-trips through Room's JSON type converters (Architecture §6.1)
 * and through `.titanprofile`/macro export files without any custom (de)serializer code.
 */
@Serializable
sealed interface InputTrigger {

    @Serializable
    data class SingleKey(val key: KeyCode) : InputTrigger

    /**
     * [modifiers] uses a canonicalized "left-or-right-doesn't-matter" modifier set
     * ([ModifierKey]) for the *combo-matching* modifiers, while [key] is the exact non-modifier
     * key that completes the chord. This mirrors how virtually every desktop remapper treats
     * "Ctrl" (either physical Ctrl key satisfies it) while still letting [SingleKey] bind the
     * left/right variants individually when a user explicitly wants that.
     */
    @Serializable
    data class Combo(val modifiers: Set<ModifierKey>, val key: KeyCode) : InputTrigger

    /** Sequential (not simultaneous) multi-key trigger, e.g. a Vim-style "g g" double-tap chord. */
    @Serializable
    data class Sequence(val keys: List<KeyCode>, val maxGapMillis: Long = 400L) : InputTrigger

    @Serializable
    data class Mouse(val button: MouseButton) : InputTrigger

    @Serializable
    data class Scroll(val direction: ScrollDirection) : InputTrigger
}

/** Modifier key family, collapsing left/right physical keys into one logical modifier for combo matching. */
@Serializable
enum class ModifierKey { SHIFT, CTRL, ALT, META }

/** How a resolved [InputTrigger] → action binding behaves over the key-down/key-up lifecycle. */
@Serializable
enum class ActionMode {
    /** Action fires on key-down, and (for things like a held movement key) stops on key-up. */
    HOLD,

    /** First press activates, second press deactivates — stays on until toggled off again. */
    TOGGLE,

    /** Fires exactly once per discrete press, regardless of how long the key is held. */
    TAP,

    /** Fires repeatedly at [repeatIntervalMillis] while the key is held down (auto-fire). */
    REPEAT
}
