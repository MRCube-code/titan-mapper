package com.titanmapper.app

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
 *
 * [EXTRA_START_ROUTE] lets another entry point -- currently only the real floating radial
 * overlay menu's [com.titanmapper.service.overlay.OverlayMenuManager] in :service, which can't
 * depend on :app and so launches this Activity by fully-qualified class name -- open straight to
 * a specific top-level screen instead of always landing on the dashboard. `singleTask` launch
 * mode (see the manifest) plus [onNewIntent] make this work even when the app is already running.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var inputBridge: ActivityInputBridge

    private var startRoute by mutableStateOf<String?>(null)
    private var startRouteNonce by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        applyStartRoute(intent)
        setContent {
            TitanTheme {
                TitanNavHost(startRoute = startRoute, startRouteNonce = startRouteNonce)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyStartRoute(intent)
    }

    private fun applyStartRoute(intent: Intent?) {
        startRoute = intent?.getStringExtra(EXTRA_START_ROUTE)
        startRouteNonce++
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        if (inputBridge.onKeyEvent(event)) true else super.dispatchKeyEvent(event)

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean =
        if (inputBridge.onGenericMotionEvent(ev)) true else super.dispatchGenericMotionEvent(ev)

    companion object {
        const val EXTRA_START_ROUTE = "start_route"
    }
}
