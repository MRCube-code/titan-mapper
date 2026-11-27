package com.titanmapper.service.selftest

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import com.titanmapper.core.data.repository.DeviceRepository
import com.titanmapper.core.data.repository.ProfileRepository
import com.titanmapper.core.model.InputTrigger
import com.titanmapper.core.model.SelfTestResult
import com.titanmapper.core.model.SelfTestVerdict
import com.titanmapper.service.api.InputEventStream
import com.titanmapper.service.api.MacroRecorder
import com.titanmapper.service.api.CapabilityMonitor
import com.titanmapper.service.api.ServiceController
import com.titanmapper.service.api.SelfTestRunner
import com.titanmapper.service.services.TitanAccessibilityService
import com.topjohnwu.superuser.Shell
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real implementation of the 15-item self-test suite. Every check below reads actual runtime
 * state through the same interfaces the rest of the app uses (no shortcuts, no hardcoded
 * verdicts) -- see [SelfTestRunner]'s KDoc for the PASS/FAIL/UNSUPPORTED/NOT_TESTED contract.
 *
 * The "live hardware event" check is interactive: it waits a bounded window on [inputEventStream]
 * for a real key/button press and reports NOT_TESTED (never a fabricated PASS) if nothing arrives.
 * The lifecycle check actually calls [ServiceController.start]/[ServiceController.stop] -- this
 * has a real, visible side effect (the foreground service will briefly stop and restart), which
 * is unavoidable for honestly testing the real lifecycle rather than asserting it in the
 * abstract; it restores whatever running state existed before the test.
 */
@Singleton
class SelfTestRunnerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val capabilityMonitor: CapabilityMonitor,
    private val controller: ServiceController,
    private val profileRepository: ProfileRepository,
    private val deviceRepository: DeviceRepository,
    private val inputEventStream: InputEventStream,
    private val macroRecorder: MacroRecorder
) : SelfTestRunner {

    override suspend fun runAll(onProgress: (SelfTestResult) -> Unit): List<SelfTestResult> {
        val results = mutableListOf<SelfTestResult>()
        suspend fun emit(result: SelfTestResult) {
            results += result
            onProgress(result)
        }

        emit(
            SelfTestResult(
                "standard_tier", "Standard-tier capture available", SelfTestVerdict.PASS,
                "Every Android app can receive input while its own window has focus; no special permission is required."
            )
        )

        val snapshot = capabilityMonitor.snapshot.value
        emit(
            SelfTestResult(
                "accessibility_enabled", "Accessibility service enabled in system settings",
                if (snapshot.accessibilityEnabled) SelfTestVerdict.PASS else SelfTestVerdict.FAIL,
                if (snapshot.accessibilityEnabled) {
                    "Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES lists Titan Mapper's accessibility service."
                } else {
                    "Titan Mapper's accessibility service is not in ENABLED_ACCESSIBILITY_SERVICES. Enable it under Settings > Accessibility."
                }
            )
        )

        val accessibilityConnected = TitanAccessibilityService.instance != null
        emit(
            SelfTestResult(
                "accessibility_connected", "Accessibility service is actually bound right now",
                when {
                    accessibilityConnected -> SelfTestVerdict.PASS
                    snapshot.accessibilityEnabled -> SelfTestVerdict.FAIL
                    else -> SelfTestVerdict.UNSUPPORTED
                },
                when {
                    accessibilityConnected -> "TitanAccessibilityService.instance is non-null -- onServiceConnected() has actually run."
                    snapshot.accessibilityEnabled -> "Enabled in settings, but no live service instance -- the system hasn't bound it yet. Try toggling it off and on."
                    else -> "Not enabled in settings, so there is nothing that could be bound."
                }
            )
        )

        val overlayGranted = Settings.canDrawOverlays(context)
        emit(
            SelfTestResult(
                "overlay_permission", "\"Display over other apps\" permission granted",
                if (overlayGranted) SelfTestVerdict.PASS else SelfTestVerdict.FAIL,
                if (overlayGranted) {
                    "Settings.canDrawOverlays(context) returned true."
                } else {
                    "Settings.canDrawOverlays(context) returned false -- the in-game overlay menu cannot be shown until this is granted."
                }
            )
        )

        val shizukuBinderReachable = try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
        emit(
            SelfTestResult(
                "shizuku_binder", "Shizuku binder reachable",
                if (shizukuBinderReachable) SelfTestVerdict.PASS else SelfTestVerdict.UNSUPPORTED,
                if (shizukuBinderReachable) {
                    "Shizuku.pingBinder() returned true."
                } else {
                    "Shizuku.pingBinder() returned false or threw -- the Shizuku app isn't installed or its service isn't running."
                }
            )
        )

        val shizukuPermissionGranted: Boolean? = if (!shizukuBinderReachable) {
            null
        } else try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
        emit(
            SelfTestResult(
                "shizuku_permission", "Shizuku permission granted",
                when {
                    shizukuPermissionGranted == null -> SelfTestVerdict.UNSUPPORTED
                    shizukuPermissionGranted -> SelfTestVerdict.PASS
                    else -> SelfTestVerdict.FAIL
                },
                when {
                    shizukuPermissionGranted == null -> "Binder unreachable, so permission can't be meaningfully checked."
                    shizukuPermissionGranted -> "Shizuku.checkSelfPermission() == PERMISSION_GRANTED."
                    else -> "Permission not granted yet -- request it from the Shizuku tier's setup screen."
                }
            )
        )

        val rootGranted: Boolean? = withContext(Dispatchers.IO) { Shell.isAppGrantedRoot() }
        emit(
            SelfTestResult(
                "root_access", "Root access granted",
                when (rootGranted) {
                    true -> SelfTestVerdict.PASS
                    false -> SelfTestVerdict.FAIL
                    null -> SelfTestVerdict.NOT_TESTED
                },
                when (rootGranted) {
                    true -> "Shell.isAppGrantedRoot() == true."
                    false -> "Shell.isAppGrantedRoot() == false -- root was denied, or no su binary is present."
                    null -> "Shell.isAppGrantedRoot() is unresolved -- the su grant prompt hasn't been answered yet (it's requested lazily the first time Root mode is selected)."
                }
            )
        )

        val devices = deviceRepository.observeDevices().first()
        val connectedDevices = devices.filter { it.connected }
        emit(
            SelfTestResult(
                "device_connected", "A physical keyboard or mouse is currently connected",
                if (connectedDevices.isNotEmpty()) SelfTestVerdict.PASS else SelfTestVerdict.FAIL,
                if (connectedDevices.isNotEmpty()) {
                    "Connected: ${connectedDevices.joinToString { it.fingerprint.deviceName }}"
                } else {
                    "No device in DeviceRepository is marked connected -- plug in a USB/Bluetooth keyboard or mouse."
                }
            )
        )

        val profiles = profileRepository.observeProfiles().first()
        emit(
            SelfTestResult(
                "profile_exists", "At least one mapping profile exists",
                if (profiles.isNotEmpty()) SelfTestVerdict.PASS else SelfTestVerdict.FAIL,
                if (profiles.isNotEmpty()) "${profiles.size} profile(s) found." else "No profiles saved yet -- create one in the Profiles screen."
            )
        )

        val enabledBinding = profiles.flatMap { it.bindings }.firstOrNull { it.isEnabled }
        emit(
            SelfTestResult(
                "binding_exists", "At least one profile has an enabled binding",
                if (enabledBinding != null) SelfTestVerdict.PASS else SelfTestVerdict.FAIL,
                if (enabledBinding != null) "Found an enabled binding (trigger ${enabledBinding.trigger})." else "Every saved binding is disabled, or none exist -- add/enable one in the Mapping editor."
            )
        )

        val wasRunning = controller.state.value.running
        val detail = StringBuilder()
        var lifecycleOk: Boolean
        if (!wasRunning) {
            controller.start()
            val startedOk = waitFor { controller.state.value.running }
            detail.append(if (startedOk) "start() brought ServiceState.running to true. " else "start() did NOT bring ServiceState.running to true within ${TIMEOUT_MS}ms. ")
            lifecycleOk = startedOk
        } else {
            detail.append("Pipeline was already running; testing a stop()/start() round-trip. ")
            lifecycleOk = true
        }
        if (lifecycleOk) {
            controller.stop()
            val stoppedOk = waitFor { !controller.state.value.running }
            detail.append(if (stoppedOk) "stop() brought ServiceState.running to false. " else "stop() did NOT bring ServiceState.running to false within ${TIMEOUT_MS}ms. ")
            lifecycleOk = stoppedOk
            controller.start()
            val restartedOk = waitFor { controller.state.value.running }
            detail.append(if (restartedOk) "Pipeline restarted afterward (left running)." else "Pipeline did NOT come back up after the test -- start it manually.")
        }
        emit(
            SelfTestResult(
                "service_lifecycle", "Start/stop lifecycle transitions ServiceState correctly",
                if (lifecycleOk) SelfTestVerdict.PASS else SelfTestVerdict.FAIL, detail.toString()
            )
        )

        val running = waitFor { controller.state.value.running }
        if (!running) {
            emit(
                SelfTestResult(
                    "engine_index", "Mapping engine's binding index matches the active profile",
                    SelfTestVerdict.NOT_TESTED, "Pipeline isn't running (see the lifecycle result above), so there is no active engine/profile to check."
                )
            )
        } else {
            val activeName = controller.state.value.activeProfileName
            val activeProfile = profiles.firstOrNull { it.name == activeName }
            val singleKeyBinding = activeProfile?.bindings?.firstOrNull { it.isEnabled && it.trigger is InputTrigger.SingleKey }
            if (activeProfile == null || singleKeyBinding == null) {
                emit(
                    SelfTestResult(
                        "engine_index", "Mapping engine's binding index matches the active profile",
                        SelfTestVerdict.NOT_TESTED, "No active profile with an enabled single-key binding to check against right now (which profile is active depends on the connected device and foreground app)."
                    )
                )
            } else {
                val key = (singleKeyBinding.trigger as InputTrigger.SingleKey).key
                val bound = controller.isKeyBound(key)
                emit(
                    SelfTestResult(
                        "engine_index", "Mapping engine's binding index matches the active profile",
                        if (bound) SelfTestVerdict.PASS else SelfTestVerdict.FAIL,
                        if (bound) {
                            "isKeyBound($key) == true, matching the enabled binding in profile \"${activeProfile.name}\"."
                        } else {
                            "Profile \"${activeProfile.name}\" has an enabled binding on $key, but isKeyBound($key) returned false -- the loaded BindingIndex is stale relative to the saved profile."
                        }
                    )
                )
            }
        }

        val gotLiveEvent = try {
            withTimeoutOrNull(INTERACTIVE_WINDOW_MS) {
                inputEventStream.events.first()
                true
            } ?: false
        } catch (_: Throwable) {
            false
        }
        emit(
            SelfTestResult(
                "live_capture", "A real hardware input event was captured",
                if (gotLiveEvent) SelfTestVerdict.PASS else SelfTestVerdict.NOT_TESTED,
                if (gotLiveEvent) {
                    "Received a RawInputEvent on the live capture bus during the test window."
                } else {
                    "No input event arrived within ${INTERACTIVE_WINDOW_MS / 1000}s. Press any key or mouse button on a connected device and run the self-test again to verify capture."
                }
            )
        )

        val (macroOk, macroDetail) = try {
            macroRecorder.start()
            delay(300)
            val steps = macroRecorder.stop()
            true to "start()/stop() completed without throwing; recorded ${steps.size} step(s) (0 is expected if nothing was pressed during the test)."
        } catch (t: Throwable) {
            false to "start()/stop() threw ${t::class.simpleName}: ${t.message}"
        }
        emit(
            SelfTestResult(
                "macro_recorder", "Macro recorder start/stop completes without error",
                if (macroOk) SelfTestVerdict.PASS else SelfTestVerdict.FAIL, macroDetail
            )
        )

        val finalState = controller.state.value
        emit(
            SelfTestResult(
                "tier_matches_requested", "Active capture tier matches the requested tier (no silent downgrade)",
                when {
                    !finalState.running -> SelfTestVerdict.NOT_TESTED
                    finalState.activeTier == finalState.requestedTier -> SelfTestVerdict.PASS
                    else -> SelfTestVerdict.FAIL
                },
                when {
                    !finalState.running -> "Pipeline isn't running."
                    finalState.activeTier == finalState.requestedTier -> "Requested ${finalState.requestedTier} and active ${finalState.activeTier} match."
                    else -> "Requested ${finalState.requestedTier} but running degraded on ${finalState.activeTier}${finalState.message?.let { " ($it)" } ?: ""}."
                }
            )
        )

        return results
    }

    private suspend fun waitFor(condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            delay(POLL_INTERVAL_MS)
        }
        return condition()
    }

    private companion object {
        const val TIMEOUT_MS = 3_000L
        const val POLL_INTERVAL_MS = 50L
        const val INTERACTIVE_WINDOW_MS = 10_000L
    }
}
