package com.titanmapper.feature.mapping

import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
        }
    }
}

/**
 * A focusable surface that captures the next physical key the user presses and reports it as a
 * [KeyCode], used by the binding editor's "press a key to bind" flow. Captures on key-down only
 * and consumes the event so it never leaks into the rest of the UI (e.g. triggering navigation).
 */
@Composable
fun TriggerCaptureBox(
    label: String,
    onKeyCaptured: (KeyCode) -> Unit,
    modifier: Modifier = Modifier
) {
    val focusRequester = remember { FocusRequester() }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.primary), RoundedCornerShape(8.dp))
            .focusRequester(focusRequester)
            .focusable()
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
