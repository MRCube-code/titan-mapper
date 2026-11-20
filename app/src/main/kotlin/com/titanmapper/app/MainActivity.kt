package com.titanmapper.app

import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.titanmapper.core.ui.theme.TitanTheme
import com.titanmapper.service.api.ActivityInputBridge
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Single-Activity host. Edge-to-edge enabled so the nav bar and status bar are drawn over our
 * content (Material 3 Scaffold handles insets automatically).
 *
 * Key/motion event forwarding to [ActivityInputBridge] supports the STANDARD permission tier,
 * which can only intercept events routed through the focused Activity window.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var inputBridge: ActivityInputBridge

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TitanTheme {
                TitanNavHost()
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        if (inputBridge.onKeyEvent(event)) true else super.dispatchKeyEvent(event)

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean =
        if (inputBridge.onGenericMotionEvent(ev)) true else super.dispatchGenericMotionEvent(ev)
}
