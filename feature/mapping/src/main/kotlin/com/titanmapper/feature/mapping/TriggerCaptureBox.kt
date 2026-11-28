package com.titanmapper.feature.mapping

import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import com.titanmapper.core.model.KeyCode

/** Android KeyEvent.KEYCODE_* -> our KeyCode, duplicated here (not shared with :service) because
 *  the editor only needs capture, never dispatch, and must not depend on the :service module's
 *  internal translator. */
internal object EditorKeyCodeTranslator {
    fun fromAndroid(code: Int): KeyCode = MAP[code] ?: KeyCode.UNKNOWN

    private val MAP: Map<Int, KeyCode> by lazy {
        buildMap {
            put(KeyEvent.KEYCODE_A, KeyCode.A); put(KeyEvent.KEYCODE_B, KeyCode.B); put(KeyEvent.KEYCODE_C, KeyCode.C)
            put(KeyEvent.KEYCODE_D, KeyCode.D); put(KeyEvent.KEYCODE_E, KeyCode.E); put(KeyEvent.KEYCODE_F, KeyCode.F)
            put(KeyEvent.KEYCODE_G, KeyCode.G); put(KeyEvent.KEYCODE_H, KeyCode.H); put(KeyEvent.KEYCODE_I, KeyCode.I)
            put(KeyEvent.KEYCODE_J, KeyCode.J); put(KeyEvent.KEYCODE_K, KeyCode.K); put(KeyEvent.KEYCODE_L, KeyCode.L)
            put(KeyEvent.KEYCODE_M, KeyCode.M); put(KeyEvent.KEYCODE_N, KeyCode.N); put(KeyEvent.KEYCODE_O, KeyCode.O)
            put(KeyEvent.KEYCODE_P, KeyCode.P); put(KeyEvent.KEYCODE_Q, KeyCode.Q); put(KeyEvent.KEYCODE_R, KeyCode.R)
            put(KeyEvent.KEYCODE_S, KeyCode.S); put(KeyEvent.KEYCODE_T, KeyCode.T); put(KeyEvent.KEYCODE_U, KeyCode.U)
            put(KeyEvent.KEYCODE_V, KeyCode.V); put(KeyEvent.KEYCODE_W, KeyCode.W); put(KeyEvent.KEYCODE_X, KeyCode.X)
            put(KeyEvent.KEYCODE_Y, KeyCode.Y); put(KeyEvent.KEYCODE_Z, KeyCode.Z)
            put(KeyEvent.KEYCODE_0, KeyCode.DIGIT_0); put(KeyEvent.KEYCODE_1, KeyCode.DIGIT_1)
            put(KeyEvent.KEYCODE_2, KeyCode.DIGIT_2); put(KeyEvent.KEYCODE_3, KeyCode.DIGIT_3)
            put(KeyEvent.KEYCODE_4, KeyCode.DIGIT_4); put(KeyEvent.KEYCODE_5, KeyCode.DIGIT_5)
            put(KeyEvent.KEYCODE_6, KeyCode.DIGIT_6); put(KeyEvent.KEYCODE_7, KeyCode.DIGIT_7)
            put(KeyEvent.KEYCODE_8, KeyCode.DIGIT_8); put(KeyEvent.KEYCODE_9, KeyCode.DIGIT_9)
            put(KeyEvent.KEYCODE_SPACE, KeyCode.SPACE); put(KeyEvent.KEYCODE_ENTER, KeyCode.ENTER)
            put(KeyEvent.KEYCODE_TAB, KeyCode.TAB); put(KeyEvent.KEYCODE_ESCAPE, KeyCode.ESCAPE)
            put(KeyEvent.KEYCODE_SHIFT_LEFT, KeyCode.SHIFT_LEFT); put(KeyEvent.KEYCODE_SHIFT_RIGHT, KeyCode.SHIFT_RIGHT)
            put(KeyEvent.KEYCODE_CTRL_LEFT, KeyCode.CTRL_LEFT); put(KeyEvent.KEYCODE_CTRL_RIGHT, KeyCode.CTRL_RIGHT)
            put(KeyEvent.KEYCODE_ALT_LEFT, KeyCode.ALT_LEFT); put(KeyEvent.KEYCODE_ALT_RIGHT, KeyCode.ALT_RIGHT)
            put(KeyEvent.KEYCODE_DPAD_UP, KeyCode.ARROW_UP); put(KeyEvent.KEYCODE_DPAD_DOWN, KeyCode.ARROW_DOWN)
            put(KeyEvent.KEYCODE_DPAD_LEFT, KeyCode.ARROW_LEFT); put(KeyEvent.KEYCODE_DPAD_RIGHT, KeyCode.ARROW_RIGHT)
            put(KeyEvent.KEYCODE_F1, KeyCode.F1); put(KeyEvent.KEYCODE_F2, KeyCode.F2); put(KeyEvent.KEYCODE_F3, KeyCode.F3)
            put(KeyEvent.KEYCODE_F4, KeyCode.F4); put(KeyEvent.KEYCODE_F5, KeyCode.F5); put(KeyEvent.KEYCODE_F6, KeyCode.F6)
            put(KeyEvent.KEYCODE_F7, KeyCode.F7); put(KeyEvent.KEYCODE_F8, KeyCode.F8); put(KeyEvent.KEYCODE_F9, KeyCode.F9)
            put(KeyEvent.KEYCODE_F10, KeyCode.F10); put(KeyEvent.KEYCODE_F11, KeyCode.F11); put(KeyEvent.KEYCODE_F12, KeyCode.F12)
            put(KeyEvent.KEYCODE_META_LEFT, KeyCode.META_LEFT); put(KeyEvent.KEYCODE_META_RIGHT, KeyCode.META_RIGHT)
            put(KeyEvent.KEYCODE_CAPS_LOCK, KeyCode.CAPS_LOCK); put(KeyEvent.KEYCODE_NUM_LOCK, KeyCode.NUM_LOCK)
            put(KeyEvent.KEYCODE_SCROLL_LOCK, KeyCode.SCROLL_LOCK)
            put(KeyEvent.KEYCODE_DEL, KeyCode.BACKSPACE); put(KeyEvent.KEYCODE_FORWARD_DEL, KeyCode.DELETE)
            put(KeyEvent.KEYCODE_INSERT, KeyCode.INSERT); put(KeyEvent.KEYCODE_MOVE_HOME, KeyCode.HOME)
            put(KeyEvent.KEYCODE_MOVE_END, KeyCode.END); put(KeyEvent.KEYCODE_PAGE_UP, KeyCode.PAGE_UP)
            put(KeyEvent.KEYCODE_PAGE_DOWN, KeyCode.PAGE_DOWN)
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
            put(KeyEvent.KEYCODE_CAMERA, KeyCode.CAMERA)
            // POWER/HOME/BACK/APP_SWITCH are intentionally absent: an app never receives them as
            // key events, so they are chosen from the phone-key chips instead (see BindingEditorSheet).
        }
    }
}

/**
 * Tap, then press any key or mouse button: the next DOWN seen on the live input stream (the same
 * stream the Diagnostics screen shows, which is known to see this app's keyboard/mouse input) is
 * reported. Listening to the stream rather than to Compose key focus is what makes mouse buttons
 * and the mouse's own G/V/F-style keys capturable at all.
 */
@Composable
fun PressToCaptureBox(
    label: String,
    events: kotlinx.coroutines.flow.Flow<com.titanmapper.core.model.RawInputEvent>,
    modifier: Modifier = Modifier,
    onKey: (KeyCode) -> Unit,
    onButton: (com.titanmapper.core.model.MouseButton) -> Unit
) {
    var armed by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val latestOnKey by androidx.compose.runtime.rememberUpdatedState(onKey)
    val latestOnButton by androidx.compose.runtime.rememberUpdatedState(onButton)
    androidx.compose.runtime.LaunchedEffect(armed) {
        if (!armed) return@LaunchedEffect
        val hit = events.first {
            (it is com.titanmapper.core.model.RawInputEvent.Key && it.action == com.titanmapper.core.model.KeyAction.DOWN) ||
                (it is com.titanmapper.core.model.RawInputEvent.Button && it.action == com.titanmapper.core.model.KeyAction.DOWN)
        }
        when (hit) {
            is com.titanmapper.core.model.RawInputEvent.Key -> latestOnKey(hit.keyCode)
            is com.titanmapper.core.model.RawInputEvent.Button -> latestOnButton(hit.button)
            else -> Unit
        }
        armed = false
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .border(
                BorderStroke(if (armed) 2.dp else 1.dp, MaterialTheme.colorScheme.primary),
                RoundedCornerShape(8.dp)
            )
            .clickable { armed = !armed },
        contentAlignment = Alignment.Center
    ) {
        Text(
            if (armed) "Press a key or mouse button now… (tap to cancel)" else label,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/**
 * A focusable surface that captures the next physical key the user presses and reports it as a
 * [KeyCode], used by the binding editor's "press a key to bind" flow. Captures on key-down only
 * and consumes the event so it never leaks into the rest of the UI (e.g. triggering navigation).
 *
 * This box used to never actually request focus: it was `.focusable()` with `onPreviewKeyEvent`,
 * but nothing ever called `FocusRequester.requestFocus()`. Android only routes hardware key events
 * to whichever element currently has focus, so with no focus request, key presses never reached
 * this box at all -- tapping it and pressing a key silently did nothing, which is exactly the
 * "binding doesn't work" symptom. Fixed by requesting focus both automatically on first
 * composition and on every tap (`clickable`), so the capture box behaves the way its own label
 * ("Tap here, then press a key") already promises.
 */
@Composable
fun TriggerCaptureBox(
    label: String,
    modifier: Modifier = Modifier,
    onKeyCaptured: (KeyCode) -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.primary), RoundedCornerShape(8.dp))
            .focusRequester(focusRequester)
            .focusable()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { focusRequester.requestFocus() }
            .onPreviewKeyEvent { event ->
                if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                    val code = EditorKeyCodeTranslator.fromAndroid(event.nativeKeyEvent.keyCode)
                    if (code != KeyCode.UNKNOWN) onKeyCaptured(code)
                    true
                } else {
                    false
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
