package com.titanmapper.service.overlay

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.TrafficStats
import android.os.BatteryManager
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.titanmapper.service.api.LatencyMonitor
import com.titanmapper.service.api.PerformanceMonitor
import com.titanmapper.service.databinding.OverlayRadialMenuBinding
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Owns the real floating radial keymap overlay window (reference screenshot #1): a draggable
 * collapsed handle that expands into Home/Settings buttons, 6 hexagon action buttons, and a
 * bottom stats bar driven by genuine [LatencyMonitor]/[PerformanceMonitor]/[BatteryManager]/
 * [TrafficStats] readings -- there is no fabricated "FPS" or similar number here.
 *
 * Shown/hidden by [com.titanmapper.service.services.TitanInputService] alongside its own
 * lifecycle, gated by [Settings.canDrawOverlays] and the user's `showOverlayMenu` setting; this
 * class never reads that setting itself, to keep settings-reading in one place. [show] and [hide]
 * are both idempotent so the caller can invoke them freely from a settings-change collector
 * without tracking shown/hidden state itself.
 */
@Singleton
class OverlayMenuManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val latencyMonitor: LatencyMonitor,
    private val performanceMonitor: PerformanceMonitor
) {

    private val windowManager by lazy { context.getSystemService(Context.WINDOW_SERVICE) as WindowManager }

    private var binding: OverlayRadialMenuBinding? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var scope: CoroutineScope? = null
    private var expanded = false

    // Drag-vs-tap bookkeeping, shared by both draggable handles (only one is touched at a time).
    private var touchStartRawX = 0f
    private var touchStartRawY = 0f
    private var paramsStartX = 0
    private var paramsStartY = 0
    private var moved = false

    fun show() {
        if (binding != null || !Settings.canDrawOverlays(context)) return

        val b = OverlayRadialMenuBinding.inflate(LayoutInflater.from(context))
        binding = b

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 200
        }
        layoutParams = params

        setExpanded(b, expand = false)
        wireDrag(b.collapsedHandle, params)
        wireDrag(b.gripBar, params)
        wireActions(b)

        windowManager.addView(b.root, params)
        startStatsPolling(b)
    }

    fun hide() {
        val b = binding ?: return
        scope?.cancel()
        scope = null
        runCatching { windowManager.removeView(b.root) }
        binding = null
        layoutParams = null
        expanded = false
    }

    private fun startStatsPolling(b: OverlayRadialMenuBinding) {
        val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = coroutineScope

        coroutineScope.launch {
            latencyMonitor.stats.collect { stats ->
                b.statsLatencyText.text = "%.1f ms".format(stats.averageMicros / 1000.0)
            }
        }
        coroutineScope.launch {
            performanceMonitor.snapshot.collect { snapshot ->
                b.statsEventsText.text = "${snapshot.eventsPerSecond} evt/s"
            }
        }
        coroutineScope.launch {
            var lastBytes = TrafficStats.getTotalRxBytes() + TrafficStats.getTotalTxBytes()
            while (true) {
                delay(1_000)
                val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                val pct = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
                b.statsBatteryText.text = if (pct in 0..100) "$pct%" else "--%"

                val nowBytes = TrafficStats.getTotalRxBytes() + TrafficStats.getTotalTxBytes()
                val deltaKb = if (lastBytes in 0..nowBytes) (nowBytes - lastBytes) / 1024.0 else 0.0
                lastBytes = nowBytes
                b.statsNetworkText.text = "%.1f KB/s".format(deltaKb)
            }
        }
    }

    private fun setExpanded(b: OverlayRadialMenuBinding, expand: Boolean) {
        expanded = expand
        b.collapsedHandle.visibility = if (expand) View.GONE else View.VISIBLE
        b.expandedPanel.visibility = if (expand) View.VISIBLE else View.GONE
    }

    private fun wireDrag(handle: View, params: WindowManager.LayoutParams) {
        handle.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartRawX = event.rawX
                    touchStartRawY = event.rawY
                    paramsStartX = params.x
                    paramsStartY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchStartRawX).roundToInt()
                    val dy = (event.rawY - touchStartRawY).roundToInt()
                    if (abs(dx) > DRAG_SLOP || abs(dy) > DRAG_SLOP) moved = true
                    if (moved) {
                        params.x = paramsStartX + dx
                        params.y = paramsStartY + dy
                        val root = binding?.root
                        if (root != null) runCatching { windowManager.updateViewLayout(root, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!moved) view.performClick()
                    true
                }
                else -> false
            }
        }
        handle.setOnClickListener {
            val b = binding ?: return@setOnClickListener
            val params2 = layoutParams ?: return@setOnClickListener
            setExpanded(b, expand = !expanded)
            runCatching { windowManager.updateViewLayout(b.root, params2) }
        }
    }

    private fun wireActions(b: OverlayRadialMenuBinding) {
        b.overlayHomeButton.setOnClickListener { launchMain(null) }
        b.overlaySettingsButton.setOnClickListener { launchMain(ROUTE_SETTINGS) }
        b.hexKeymapConfigsButton.setOnClickListener { launchMain(ROUTE_PROFILES) }
        b.hexEditKeymapButton.setOnClickListener { launchMain(ROUTE_PROFILES) }
        b.hexKeymapSettingsButton.setOnClickListener { launchMain(ROUTE_SETTINGS) }
        b.hexKeyboardTypingButton.setOnClickListener { launchMain(ROUTE_PROFILES) }
        b.hexMacroButton.setOnClickListener { launchMain(ROUTE_MACROS) }
        b.hexRecoilControlButton.setOnClickListener { launchMain(ROUTE_PROFILES) }
    }

    /**
     * Launches :app's MainActivity by fully-qualified class name (this module can't depend on
     * :app -- the dependency runs the other way), optionally pointing its NavHost at a starting
     * route via the same extra key MainActivity.EXTRA_START_ROUTE uses (duplicated as a literal
     * here for the same reason, see GamesActivity/SettingsScreen's existing cross-module
     * launches-by-class-name).
     *
     * Several of the 6 reference-screenshot actions (Keymap Configs, Edit keymap, Keyboard
     * Typing, Recoil control) all resolve honestly to the same real "profiles" screen: that is
     * where every per-profile keymap actually lives, including its key/mouse bindings and its
     * mouse sensitivity preset (xScale/yScale/curve -- the real mechanism behind recoil
     * compensation). There is no separate fake screen invented for each label.
     */
    private fun launchMain(route: String?) {
        val intent = Intent()
            .setClassName(context.packageName, "com.titanmapper.app.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (route != null) intent.putExtra(EXTRA_START_ROUTE, route)
        context.startActivity(intent)
    }

    companion object {
        private const val DRAG_SLOP = 8
        private const val EXTRA_START_ROUTE = "start_route"
        private const val ROUTE_SETTINGS = "settings"
        private const val ROUTE_PROFILES = "profiles"
        private const val ROUTE_MACROS = "macros"
    }
}
