package com.titanmapper.core.model

/**
 * Titan Mapper's own device-agnostic key code space.
 *
 * We deliberately do NOT reuse `android.view.KeyEvent.KEYCODE_*` constants here because
 * `:core:model` is a pure-Kotlin/JVM module with zero Android SDK dependency (see module
 * build.gradle.kts for the rationale: unit-testability without an emulator, and portability
 * if a future capture tier reads raw Linux evdev codes directly, which use an entirely
 * different numbering scheme — see [LinuxEvdevCode]).
 *
 * The mapping between `android.view.KeyEvent` constants and [KeyCode], and between raw evdev
 * `KEY_*` codes and [KeyCode], both live in the `:service` module's capture adapters
 * (`AndroidKeyCodeTranslator` and `EvdevKeyCodeTranslator` respectively) — this enum is the
 * single canonical vocabulary every tier normalizes into before anything touches the
 * MappingEngine.
 *
 * Only a representative, production-relevant subset is enumerated (letters, digits, function
 * keys, modifiers, navigation, numpad, media, and common gaming-relevant keys). The list is
 * intentionally flat (not nested) so it serializes to a stable, readable JSON string via
 * `kotlinx.serialization`'s enum-name default encoding — critical for `.titanprofile` export
 * forward-compatibility (Section 6.3 of the architecture doc).
 */
enum class KeyCode {
    // --- Letters ---
    A, B, C, D, E, F, G, H, I, J, K, L, M,
    N, O, P, Q, R, S, T, U, V, W, X, Y, Z,

    // --- Digits (top row) ---
    DIGIT_0, DIGIT_1, DIGIT_2, DIGIT_3, DIGIT_4,
    DIGIT_5, DIGIT_6, DIGIT_7, DIGIT_8, DIGIT_9,

    // --- Function row ---
    F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12,

    // --- Modifiers (left/right distinguished — important for combo mapping) ---
    SHIFT_LEFT, SHIFT_RIGHT,
    CTRL_LEFT, CTRL_RIGHT,
    ALT_LEFT, ALT_RIGHT,
    META_LEFT, META_RIGHT, // "Windows"/"Command" key
    CAPS_LOCK, NUM_LOCK, SCROLL_LOCK,

    // --- Whitespace / editing ---
    SPACE, ENTER, TAB, BACKSPACE, DELETE, ESCAPE,
    INSERT, HOME, END, PAGE_UP, PAGE_DOWN,

    // --- Arrows ---
    ARROW_UP, ARROW_DOWN, ARROW_LEFT, ARROW_RIGHT,

    // --- Punctuation / symbols ---
    MINUS, EQUALS, LEFT_BRACKET, RIGHT_BRACKET, BACKSLASH,
    SEMICOLON, APOSTROPHE, GRAVE, COMMA, PERIOD, SLASH,

    // --- Numpad ---
    NUMPAD_0, NUMPAD_1, NUMPAD_2, NUMPAD_3, NUMPAD_4,
    NUMPAD_5, NUMPAD_6, NUMPAD_7, NUMPAD_8, NUMPAD_9,
    NUMPAD_DIVIDE, NUMPAD_MULTIPLY, NUMPAD_SUBTRACT, NUMPAD_ADD,
    NUMPAD_ENTER, NUMPAD_DECIMAL,

    // --- Media / system (common on gaming keyboards) ---
    MEDIA_PLAY_PAUSE, MEDIA_NEXT, MEDIA_PREVIOUS,
    VOLUME_UP, VOLUME_DOWN, VOLUME_MUTE,
    PRINT_SCREEN, PAUSE_BREAK, MENU,

    // --- Phone hardware / system keys ---
    // Needed so the phone's own buttons can be remapped (e.g. Volume Up -> Power). NAV_BACK and
    // NAV_HOME are deliberately separate from the keyboard's BACKSPACE/HOME so a keyboard's Home
    // key and the phone's Home action can never be confused for one another in a binding.
    POWER, NAV_BACK, NAV_HOME, APP_SWITCH, CAMERA, BRIGHTNESS_UP, BRIGHTNESS_DOWN,

    /** Sentinel for a physical key our translator saw but doesn't yet have a mapping for. */
    UNKNOWN
}

/** Mouse buttons, independent of [KeyCode] since mice report through a different axis set. */
enum class MouseButton {
    LEFT, RIGHT, MIDDLE,
    BACK, FORWARD, // side buttons, common on gaming mice
    EXTRA_1, EXTRA_2 // additional programmable buttons on high-end mice
}

/** Direction of a discrete scroll-wheel tick (as opposed to continuous [ScrollDelta]). */
enum class ScrollDirection { UP, DOWN, LEFT, RIGHT }
