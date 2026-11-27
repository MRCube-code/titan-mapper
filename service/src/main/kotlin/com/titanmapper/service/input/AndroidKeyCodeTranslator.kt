package com.titanmapper.service.input

import android.view.KeyEvent
import com.titanmapper.core.model.KeyCode

/**
 * Translates `android.view.KeyEvent.KEYCODE_*` into our device-agnostic [KeyCode] (Architecture
 * §4.6 — the capture-adapter boundary is the only place Android key constants are allowed to
 * touch the mapping engine). Deliberately exhaustive `when` with no `else`, so adding a
 * [KeyCode] without updating this file is a compile error, not a silent gap.
 */
object AndroidKeyCodeTranslator {

    fun fromAndroid(code: Int): KeyCode = ANDROID_TO_TITAN[code] ?: KeyCode.UNKNOWN

    fun toAndroid(key: KeyCode): Int? = TITAN_TO_ANDROID[key]

    private val ANDROID_TO_TITAN: Map<Int, KeyCode> = buildMap {
        put(KeyEvent.KEYCODE_A, KeyCode.A); put(KeyEvent.KEYCODE_B, KeyCode.B)
        put(KeyEvent.KEYCODE_C, KeyCode.C); put(KeyEvent.KEYCODE_D, KeyCode.D)
        put(KeyEvent.KEYCODE_E, KeyCode.E); put(KeyEvent.KEYCODE_F, KeyCode.F)
        put(KeyEvent.KEYCODE_G, KeyCode.G); put(KeyEvent.KEYCODE_H, KeyCode.H)
        put(KeyEvent.KEYCODE_I, KeyCode.I); put(KeyEvent.KEYCODE_J, KeyCode.J)
        put(KeyEvent.KEYCODE_K, KeyCode.K); put(KeyEvent.KEYCODE_L, KeyCode.L)
        put(KeyEvent.KEYCODE_M, KeyCode.M); put(KeyEvent.KEYCODE_N, KeyCode.N)
        put(KeyEvent.KEYCODE_O, KeyCode.O); put(KeyEvent.KEYCODE_P, KeyCode.P)
        put(KeyEvent.KEYCODE_Q, KeyCode.Q); put(KeyEvent.KEYCODE_R, KeyCode.R)
        put(KeyEvent.KEYCODE_S, KeyCode.S); put(KeyEvent.KEYCODE_T, KeyCode.T)
        put(KeyEvent.KEYCODE_U, KeyCode.U); put(KeyEvent.KEYCODE_V, KeyCode.V)
        put(KeyEvent.KEYCODE_W, KeyCode.W); put(KeyEvent.KEYCODE_X, KeyCode.X)
        put(KeyEvent.KEYCODE_Y, KeyCode.Y); put(KeyEvent.KEYCODE_Z, KeyCode.Z)
        put(KeyEvent.KEYCODE_0, KeyCode.DIGIT_0); put(KeyEvent.KEYCODE_1, KeyCode.DIGIT_1)
        put(KeyEvent.KEYCODE_2, KeyCode.DIGIT_2); put(KeyEvent.KEYCODE_3, KeyCode.DIGIT_3)
        put(KeyEvent.KEYCODE_4, KeyCode.DIGIT_4); put(KeyEvent.KEYCODE_5, KeyCode.DIGIT_5)
        put(KeyEvent.KEYCODE_6, KeyCode.DIGIT_6); put(KeyEvent.KEYCODE_7, KeyCode.DIGIT_7)
        put(KeyEvent.KEYCODE_8, KeyCode.DIGIT_8); put(KeyEvent.KEYCODE_9, KeyCode.DIGIT_9)
        put(KeyEvent.KEYCODE_F1, KeyCode.F1); put(KeyEvent.KEYCODE_F2, KeyCode.F2)
        put(KeyEvent.KEYCODE_F3, KeyCode.F3); put(KeyEvent.KEYCODE_F4, KeyCode.F4)
        put(KeyEvent.KEYCODE_F5, KeyCode.F5); put(KeyEvent.KEYCODE_F6, KeyCode.F6)
        put(KeyEvent.KEYCODE_F7, KeyCode.F7); put(KeyEvent.KEYCODE_F8, KeyCode.F8)
        put(KeyEvent.KEYCODE_F9, KeyCode.F9); put(KeyEvent.KEYCODE_F10, KeyCode.F10)
        put(KeyEvent.KEYCODE_F11, KeyCode.F11); put(KeyEvent.KEYCODE_F12, KeyCode.F12)
        put(KeyEvent.KEYCODE_SHIFT_LEFT, KeyCode.SHIFT_LEFT); put(KeyEvent.KEYCODE_SHIFT_RIGHT, KeyCode.SHIFT_RIGHT)
        put(KeyEvent.KEYCODE_CTRL_LEFT, KeyCode.CTRL_LEFT); put(KeyEvent.KEYCODE_CTRL_RIGHT, KeyCode.CTRL_RIGHT)
        put(KeyEvent.KEYCODE_ALT_LEFT, KeyCode.ALT_LEFT); put(KeyEvent.KEYCODE_ALT_RIGHT, KeyCode.ALT_RIGHT)
        put(KeyEvent.KEYCODE_META_LEFT, KeyCode.META_LEFT); put(KeyEvent.KEYCODE_META_RIGHT, KeyCode.META_RIGHT)
        put(KeyEvent.KEYCODE_CAPS_LOCK, KeyCode.CAPS_LOCK); put(KeyEvent.KEYCODE_NUM_LOCK, KeyCode.NUM_LOCK)
        put(KeyEvent.KEYCODE_SCROLL_LOCK, KeyCode.SCROLL_LOCK)
        put(KeyEvent.KEYCODE_SPACE, KeyCode.SPACE); put(KeyEvent.KEYCODE_ENTER, KeyCode.ENTER)
        put(KeyEvent.KEYCODE_TAB, KeyCode.TAB); put(KeyEvent.KEYCODE_DEL, KeyCode.BACKSPACE)
        put(KeyEvent.KEYCODE_FORWARD_DEL, KeyCode.DELETE); put(KeyEvent.KEYCODE_ESCAPE, KeyCode.ESCAPE)
        put(KeyEvent.KEYCODE_INSERT, KeyCode.INSERT); put(KeyEvent.KEYCODE_MOVE_HOME, KeyCode.HOME)
        put(KeyEvent.KEYCODE_MOVE_END, KeyCode.END); put(KeyEvent.KEYCODE_PAGE_UP, KeyCode.PAGE_UP)
        put(KeyEvent.KEYCODE_PAGE_DOWN, KeyCode.PAGE_DOWN)
        put(KeyEvent.KEYCODE_DPAD_UP, KeyCode.ARROW_UP); put(KeyEvent.KEYCODE_DPAD_DOWN, KeyCode.ARROW_DOWN)
        put(KeyEvent.KEYCODE_DPAD_LEFT, KeyCode.ARROW_LEFT); put(KeyEvent.KEYCODE_DPAD_RIGHT, KeyCode.ARROW_RIGHT)
        put(KeyEvent.KEYCODE_MINUS, KeyCode.MINUS); put(KeyEvent.KEYCODE_EQUALS, KeyCode.EQUALS)
        put(KeyEvent.KEYCODE_LEFT_BRACKET, KeyCode.LEFT_BRACKET); put(KeyEvent.KEYCODE_RIGHT_BRACKET, KeyCode.RIGHT_BRACKET)
        put(KeyEvent.KEYCODE_BACKSLASH, KeyCode.BACKSLASH); put(KeyEvent.KEYCODE_SEMICOLON, KeyCode.SEMICOLON)
        put(KeyEvent.KEYCODE_APOSTROPHE, KeyCode.APOSTROPHE); put(KeyEvent.KEYCODE_GRAVE, KeyCode.GRAVE)
        put(KeyEvent.KEYCODE_COMMA, KeyCode.COMMA); put(KeyEvent.KEYCODE_PERIOD, KeyCode.PERIOD)
        put(KeyEvent.KEYCODE_SLASH, KeyCode.SLASH)
        put(KeyEvent.KEYCODE_NUMPAD_0, KeyCode.NUMPAD_0); put(KeyEvent.KEYCODE_NUMPAD_1, KeyCode.NUMPAD_1)
        put(KeyEvent.KEYCODE_NUMPAD_2, KeyCode.NUMPAD_2); put(KeyEvent.KEYCODE_NUMPAD_3, KeyCode.NUMPAD_3)
        put(KeyEvent.KEYCODE_NUMPAD_4, KeyCode.NUMPAD_4); put(KeyEvent.KEYCODE_NUMPAD_5, KeyCode.NUMPAD_5)
        put(KeyEvent.KEYCODE_NUMPAD_6, KeyCode.NUMPAD_6); put(KeyEvent.KEYCODE_NUMPAD_7, KeyCode.NUMPAD_7)
        put(KeyEvent.KEYCODE_NUMPAD_8, KeyCode.NUMPAD_8); put(KeyEvent.KEYCODE_NUMPAD_9, KeyCode.NUMPAD_9)
        put(KeyEvent.KEYCODE_NUMPAD_DIVIDE, KeyCode.NUMPAD_DIVIDE); put(KeyEvent.KEYCODE_NUMPAD_MULTIPLY, KeyCode.NUMPAD_MULTIPLY)
        put(KeyEvent.KEYCODE_NUMPAD_SUBTRACT, KeyCode.NUMPAD_SUBTRACT); put(KeyEvent.KEYCODE_NUMPAD_ADD, KeyCode.NUMPAD_ADD)
        put(KeyEvent.KEYCODE_NUMPAD_ENTER, KeyCode.NUMPAD_ENTER); put(KeyEvent.KEYCODE_NUMPAD_DOT, KeyCode.NUMPAD_DECIMAL)
        put(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyCode.MEDIA_PLAY_PAUSE); put(KeyEvent.KEYCODE_MEDIA_NEXT, KeyCode.MEDIA_NEXT)
        put(KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyCode.MEDIA_PREVIOUS)
        put(KeyEvent.KEYCODE_VOLUME_UP, KeyCode.VOLUME_UP); put(KeyEvent.KEYCODE_VOLUME_DOWN, KeyCode.VOLUME_DOWN)
        put(KeyEvent.KEYCODE_VOLUME_MUTE, KeyCode.VOLUME_MUTE)
        put(KeyEvent.KEYCODE_SYSRQ, KeyCode.PRINT_SCREEN); put(KeyEvent.KEYCODE_BREAK, KeyCode.PAUSE_BREAK)
        put(KeyEvent.KEYCODE_MENU, KeyCode.MENU)
        // Phone hardware / system keys. Capture-side note: a mouse's M4/M5 that arrives as
        // KEYCODE_BACK/FORWARD is intercepted by the capture tiers BEFORE this table is consulted
        // (see ActivityInputBridgeImpl / TitanAccessibilityService), so NAV_BACK here only ever
        // means a real phone/keyboard Back key, and a tier only swallows it if it is bound.
        put(KeyEvent.KEYCODE_POWER, KeyCode.POWER); put(KeyEvent.KEYCODE_BACK, KeyCode.NAV_BACK)
        put(KeyEvent.KEYCODE_HOME, KeyCode.NAV_HOME); put(KeyEvent.KEYCODE_APP_SWITCH, KeyCode.APP_SWITCH)
        put(KeyEvent.KEYCODE_CAMERA, KeyCode.CAMERA)
        put(KeyEvent.KEYCODE_BRIGHTNESS_UP, KeyCode.BRIGHTNESS_UP); put(KeyEvent.KEYCODE_BRIGHTNESS_DOWN, KeyCode.BRIGHTNESS_DOWN)
    }

    private val TITAN_TO_ANDROID: Map<KeyCode, Int> =
        ANDROID_TO_TITAN.entries.associate { (android, titan) -> titan to android }
}
