# Titan Mapper — Software Architecture Document

## 1. Vision

Titan Mapper turns any Android device (phone, tablet, TV box, emulator-style handheld) into a
full keyboard/mouse-driven platform. A physical keyboard and mouse — connected over USB‑OTG,
Bluetooth HID, or a 2.4 GHz wireless dongle — are captured, remapped, and re-dispatched as
touch/game input, macros, or system actions, with per-app, per-game profiles and sub-5ms
added latency.

## 2. High-Level Module Graph

```
                        ┌──────────────────────┐
                        │        :app          │  (Application, nav graph, DI root)
                        └──────────┬────────────┘
             ┌───────────┬─────────┼─────────┬───────────┬────────────┐
             ▼           ▼         ▼         ▼           ▼            ▼
      :feature:dashboard :feature:devices :feature:mapping :feature:profiles ...
             │           │         │         │           │            │
             └───────────┴─────────┴────┬────┴───────────┴────────────┘
                                         ▼
                              ┌─────────────────────┐
                              │      :service        │  (foreground services,
                              │  input / engine /     │   InputReader, overlay,
                              │  overlay / permission │   permission brokers)
                              └──────────┬────────────┘
                                         ▼
                 ┌───────────────┬───────────────┬────────────────┐
                 ▼               ▼               ▼                ▼
            :core:model     :core:database   :core:data      :core:ui
            (pure Kotlin     (Room entities,  (repositories,  (Compose theme,
             data classes,    DAOs,            flows, use      Material3,
             enums, sealed    migrations)       cases)          shared widgets)
             interfaces)
                 │               │               │                │
                 └───────────────┴───────────────┴────────────────┘
                                         ▼
                                  :core:common
                        (dispatchers, Result wrapper, logging,
                         extension functions, constants)
```

**Dependency rule:** arrows point *downward only*. `:core:*` modules never depend on
`:feature:*` or `:service`. `:feature:*` modules never depend on each other directly — they
communicate through `:core:data` repositories and a navigation contract in `:app`. This keeps
build times low (parallel module compilation) and enforces testability.

## 3. Why Modularize This Much?

- **Gradle configuration caching** — isolating the input-handling code (`:service`) from UI
  modules means changing a Compose screen never recompiles the latency-critical event pipeline.
- **Testability** — `:core:model` and `:core:data` are pure-Kotlin/JVM-testable without an
  emulator; `:service` has Robolectric-friendly seams via interfaces.
- **Plugin architecture** — third-party "mapping plugins" (Section 14) compile against
  `:core:model` and `:core:data` public APIs only, never the service internals.

## 4. Input Pipeline (the heart of the system)

### 4.1 Why Android input is hard

Android deliberately restricts third-party apps from doing what a desktop input remapper does:

| Capability | Standard App | Accessibility Service | Shizuku | Root |
|---|---|---|---|---|
| Read raw `KeyEvent`/`MotionEvent` for **your own window** | ✅ | ✅ | ✅ | ✅ |
| Read events **system-wide**, across all apps | ❌ | ⚠️ partial (`AccessibilityEvent`, not raw HID) | ✅ (via `InputManager` shell perms) | ✅ (via `/dev/input/eventX` direct) |
| **Inject** synthetic key/motion events into other apps | ❌ | ⚠️ limited (dispatchGesture, global actions only) | ✅ (`injectInputEvent` via Shizuku bridge to `InputManager`) | ✅ (`uinput` virtual device or `/dev/input` write) |
| Distinguish **which physical device** sent a key (multi-keyboard) | ✅ via `KeyEvent.getDeviceId()` *if app has focus* | ✅ | ✅ | ✅ |
| Grab exclusive access (prevent OS from also acting on the key) | ❌ | ❌ | ⚠️ partial | ✅ (`EVIOCGRAB` ioctl) |
| Works without any special permission grant | ✅ | ❌ (user must enable in Settings) | ❌ (needs Shizuku app + ADB/root pairing) | ❌ (needs rooted device) |

This is why Titan Mapper ships **four permission tiers** rather than picking one — each trades
capability for friction, and we must gracefully fall back.

### 4.2 Tier 1 — Standard Mode (`InputManager` + in-app capture)

- Uses `android.hardware.input.InputManager` and `InputDevice` APIs to **enumerate** connected
  keyboards/mice (`InputManager.getInputDeviceIds()`), read their `InputDevice.getSources()`
  bitmask (`SOURCE_KEYBOARD`, `SOURCE_MOUSE`, `SOURCE_TRACKBALL`), and register an
  `InputManager.InputDeviceListener` for hotplug add/remove/change.
- Captures events **only while Titan Mapper's own window has focus** — this is an OS guarantee
  we cannot bypass without elevated access. We handle this via `Activity.dispatchKeyEvent`,
  `dispatchGenericMotionEvent`, and a transparent, always-on-top, **touch-through except for
  input capture**, `TYPE_APPLICATION_OVERLAY` window when the user explicitly wants "remap
  while using any app" behavior in this tier — clearly labelled in UI as *limited: works only
  when Titan Mapper is the active/focused window, or via the overlay capture layer which still
  cannot inject into arbitrary apps.*
- Best used for: configuring profiles, testing mappings in a sandbox, and driving the
  **Titan Mapper launcher/dashboard itself** with K&M.
- Output: mapped actions are dispatched back as synthetic **touch gestures inside our own
  window** (e.g., a virtual d-pad overlay you built) — safe, permission-free, zero-root.

### 4.3 Tier 2 — Accessibility Service Mode

- `TitanAccessibilityService extends AccessibilityService` declared with
  `canPerformGestures="true"`, `canRetrieveWindowContent` not required (we don't need content,
  only control).
- System-wide key interception: as of Android 11+, enabling
  `AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS` plus overriding
  `onKeyEvent(KeyEvent)` lets an accessibility service **intercept and consume hardware key
  events system-wide**, before the foreground app sees them — this is the only
  no-root/no-ADB path to true system-wide key remapping on stock Android.
- Mouse/motion is **not** covered by `onKeyEvent` (that's keys only). For system-wide pointer
  remapping without root, we combine: (a) reading relative motion via a focused, mostly
  invisible 1×1 `SYSTEM_ALERT_WINDOW`/overlay that requests pointer capture
  (`View.requestPointerCapture()`, API 26+) to get raw unaccelerated deltas, with (b)
  `dispatchGesture()` to inject synthetic touch/drag into the active app as a consequence.
- Limitations we surface honestly in-app: `dispatchGesture` cannot inject raw `KeyEvent`s into
  other apps (no key injection, only gesture injection), gesture injection has a minimum
  duration/latency floor (~a few ms per gesture segment, OS-scheduled), and some OEM skins
  (MIUI, OneUI) throttle or kill long-lived AccessibilityServices unless battery-optimization
  is disabled for the app — we detect the OEM and show a guided whitelist flow.

### 4.4 Tier 3 — Shizuku Enhanced Mode

- Integrates `rikka.shizuku:api` + `rikka.shizuku:provider`. Communicates with the
  Shizuku privileged process (itself started once via `adb shell` or root) over Binder.
- Grants access to hidden/system APIs normally gated by `signature|privileged` permissions:
  - `android.permission.INJECT_EVENTS` → lets us call the hidden
    `InputManager.injectInputEvent(InputEvent, int mode)` to inject **both** KeyEvents and
    MotionEvents system-wide, as if from a real HID device.
  - `android.permission.READ_FRAME_BUFFER` / `android.permission.MANAGE_ACTIVITY_TASKS` are
    *not* needed for input but are stubbed in the permission broker for future features
    (screen-read based context switching).
- We build a `ShizukuInputInjector` that wraps `IInputManager` AIDL stub obtained via
  `Shizuku.bindUserService` or direct hidden-API reflection gated behind
  `HiddenApiBypass` (to survive Android's hidden API greylist/blacklist enforcement, since
  Shizuku-granted callers run with elevated UID but still go through the same ART
  reflection path — we document the Android-version-specific allowances in code comments).
- This tier gives near-root capability with **no rooting required**, at the cost of the user
  needing to pair Shizuku once per boot (or run its wireless-debugging/always-on service on
  Android 11+ via `adb pair`), which we streamline with an in-app deep link to Shizuku's
  activation activity and a live "Shizuku connected" status chip.

### 4.5 Tier 4 — Root Enhanced Mode

- Highest capability, lowest friction once granted. Two complementary strategies, both
  implemented, auto-selected by capability probing at runtime:
  1. **`uinput` virtual HID device** — Titan Mapper's root helper (`libsu`-shell) creates a
     `/dev/uinput`-backed virtual keyboard+mouse+gamepad device at startup. All *mapped output*
     (the result of macros/remaps) is written as native HID reports. This is **the single
     highest-fidelity output path**: apps and games see it exactly like a real USB device (no
     accessibility/gesture quirks, true relative mouse deltas, arbitrary scan codes, N-key
     rollover).
  2. **Direct `/dev/input/eventX` grab** — for *input capture* (not output), root lets us
     `open()` the raw evdev node for each physical keyboard/mouse (enumerated via
     `/proc/bus/input/devices`), optionally `ioctl(fd, EVIOCGRAB, 1)` to **exclusively grab**
     the device so the Android input stack never sees the raw events at all (eliminating
     double-input / ghost-touch issues common in other remappers), parse `input_event` structs
     ourselves in native code for sub-millisecond jitter, then feed them into the same mapping
     engine used by the other tiers.
- Root commands are executed through `libsu` (`com.topjohnwu.superuser`), never raw
  `Runtime.exec("su")`, for proper shell session reuse, stdout/stderr capture, and SELinux-aware
  error reporting.
- Full fallback chain is reflected in `PermissionTier` (Section 7) — if root is revoked mid
  session (e.g., Magisk hide toggled), the engine demotes gracefully to Shizuku → Accessibility
  → Standard without crashing, dropping only the capabilities that tier no longer supports.

### 4.6 Unified Capture → Mapping → Dispatch Pipeline

Regardless of tier, every raw input event funnels into one shape so the Mapping Engine never
needs to know which tier produced it:

```
 Physical Device
      │
      ▼
 ┌─────────────────────┐   Tier 1: InputManager/dispatchKeyEvent
 │   Tier-specific      │   Tier 2: AccessibilityService.onKeyEvent + PointerCapture
 │   Capture Adapter    │   Tier 3: Shizuku-bridged InputManager listeners
 │  (implements          │   Tier 4: native evdev reader (JNI) over /dev/input/eventX
 │   InputCaptureSource)│
 └──────────┬───────────┘
            ▼  emits
    RawInputEvent (sealed class: KeyEvent | MotionEvent | ScrollEvent)
    tagged with: deviceId, physicalDeviceFingerprint, monotonicNanos, source tier
            ▼
 ┌─────────────────────┐
 │   InputEventBus      │  (Kotlin SharedFlow, replay=0, extraBufferCapacity=64,
 │   (hot flow, single  │   DROP_OLDEST backpressure — never block the capture thread)
 │   producer per tier) │
 └──────────┬───────────┘
            ▼
 ┌─────────────────────┐
 │   MappingEngine      │  pure Kotlin, no Android deps beyond key-code constants:
 │  - profile lookup    │  resolves active Profile for (deviceFingerprint, foregroundPackage)
 │  - binding resolver  │  binding = KeyCombo -> MappedAction
 │  - state machine     │  tracks hold/toggle/tap/repeat/combo state per binding
 │  - macro trigger     │  fires MacroExecutor when a binding resolves to a Macro
 └──────────┬───────────┘
            ▼
    MappedAction (sealed: TouchTap | TouchDrag | KeyInjection | SystemAction | MacroRun | AppLaunch)
            ▼
 ┌─────────────────────┐
 │  DispatchAdapter      │  Tier 1: internal gesture simulation on virtual D-pad overlay
 │ (implements           │  Tier 2: AccessibilityService.dispatchGesture()
 │  OutputDispatchSink)  │  Tier 3: Shizuku IInputManager.injectInputEvent()
 │                       │  Tier 4: uinput HID report write (native)
 └───────────────────────┘
```

Key properties:
- **Single mapping engine, pluggable capture & dispatch.** Adding a 5th tier later (e.g. a
  future public `INJECT_EVENTS` permission) means writing one new adapter pair, zero changes
  to mapping/profile/macro logic.
- **Device fingerprinting** (`vendorId:productId:deviceName` hash) lets a profile bind to a
  *specific physical keyboard model*, not just "any keyboard", enabling the "two keyboards,
  two different profiles" multi-device requirement.
- **Backpressure-safe hot flow.** The capture thread (often a native JNI callback at >1000Hz
  for gaming mice) must never block; `SharedFlow` with `DROP_OLDEST` plus a dedicated
  `Dispatchers.Default` consumer coroutine guarantees the OS-facing capture callback returns
  in microseconds.

## 5. Latency Budget

| Stage | Target | Technique |
|---|---|---|
| Capture → EventBus | < 0.3 ms | Lock-free `SharedFlow.tryEmit`, no allocations on hot path (reuse mutable event pool) |
| EventBus → Engine resolve | < 0.2 ms | Precompiled `HashMap<KeyCombo, MappedAction>` per profile, rebuilt only on profile swap, O(1) lookup |
| Engine → Dispatch (Tier 4/root) | < 1 ms | Direct `write()` to uinput fd, no IPC |
| Engine → Dispatch (Tier 3/Shizuku) | < 3 ms | Binder call to Shizuku; batched when possible |
| Engine → Dispatch (Tier 2/Accessibility) | ~8–16 ms | Bound by `dispatchGesture` minimum stroke duration (OS-imposed) |
| **End-to-end target (Root/Shizuku)** | **< 5 ms** | |
| **End-to-end target (Accessibility)** | **< 20 ms**, acceptable for productivity, marked "not recommended for competitive gaming" in UI | |

## 6. Data Layer

### 6.1 Room Schema (see `:core:database`)

- `DeviceEntity` — fingerprint, display name, type (KEYBOARD/MOUSE/HYBRID), last-seen tier,
  vendor/product ids, user nickname/icon.
- `ProfileEntity` — id, name, targetPackage (nullable = global), deviceFingerprint (nullable =
  applies to all devices of matching type), isActive, priority (for auto-switch conflict
  resolution), createdAt/updatedAt, color tag.
- `BindingEntity` — profileId FK, triggerJson (serialized `KeyCombo`/`MouseButton`/`ScrollDir`),
  actionJson (serialized `MappedAction`), actionMode (HOLD/TOGGLE/TAP/REPEAT), repeatIntervalMs.
- `MacroEntity` — id, name, stepsJson (ordered list of `MacroStep`: KeyPress, Delay, LoopStart,
  LoopEnd, Conditional), loopCount, isGlobal.
- `OverlayLayoutEntity` — profileId FK, controlsJson (position/size/transparency per virtual
  control), orientation (PORTRAIT/LANDSCAPE, stored separately so rotation keeps both).
- `SensitivityPresetEntity` — curve type (LINEAR/EXPONENTIAL/CUSTOM_BEZIER), xScale, yScale,
  accelerationExponent, rawInputEnabled, dpiReference.
- `EventLogEntity` — rolling ring-buffer table (capped at N rows via trigger+delete, default
  5,000) for the diagnostics/event-logger feature; never grows unbounded.

Type converters (`:core:database/converter`) use `kotlinx.serialization` (not Gson — smaller,
compile-time-checked, no reflection) to (de)serialize the sealed `KeyCombo`/`MappedAction`
class hierarchies to/from JSON columns.

### 6.2 Repository Layer (`:core:data`)

Repositories expose `Flow<T>` from Room DAOs directly (Room's Flow support), wrapped in a
`Result`-style sealed `DataResult<T>` for explicit error channels (`DataResult.Success`,
`DataResult.Error(cause, isRecoverable)`), never throwing across the repository boundary.
`ProfileRepository.observeActiveProfile(deviceFingerprint, foregroundPackage)` is the single
hot path the `MappingEngine` subscribes to for automatic profile switching.

### 6.3 Cloud Backup Architecture

- Backup payload = full JSON export (profiles + bindings + macros + overlay layouts) signed
  with a local HMAC (key derived from Android Keystore) to detect tampering, then optionally
  uploaded via a pluggable `CloudBackupProvider` interface. Ships with:
  - `GoogleDriveBackupProvider` (uses Drive's app-data folder scope — invisible to the user's
    normal Drive UI, no broad storage permission needed).
  - `LocalFileBackupProvider` (SAF `ACTION_CREATE_DOCUMENT`/`ACTION_OPEN_DOCUMENT` — the
    always-available fallback, zero network permission).
  - Interface is intentionally small (`suspend fun upload(blob): DataResult<BackupHandle>`,
    `suspend fun download(handle): DataResult<ByteArray>`, `suspend fun list(): DataResult<List<BackupHandle>>`)
    so a Dropbox/self-hosted-WebDAV provider can be added later without touching call sites.
- **Profile sharing** reuses the export format: a single profile (not the whole DB) serializes
  to a self-contained `.titanprofile` JSON (schema-versioned, with a `minAppVersion` field for
  forward-compat checks), shareable via `Intent.ACTION_SEND` or QR code (for couch-to-couch
  device sharing without any network).

## 7. Permission & Capability Model (`:service/permission`)

```kotlin
sealed class PermissionTier(val level: Int) {
    object Standard : PermissionTier(0)
    object Accessibility : PermissionTier(1)
    object Shizuku : PermissionTier(2)
    object Root : PermissionTier(3)
}
```

A `CapabilityProbe` runs at app start and whenever the user toggles a tier, checking in order:
root (`Shell.isAppGrantedRoot()` via libsu, cached), Shizuku (`Shizuku.pingBinder()` +
permission check), Accessibility (`Settings.Secure.ACCESSIBILITY_ENABLED` + service id in the
enabled list), falling back to Standard always-available. The **highest available tier is not
auto-selected silently** — the user explicitly picks their desired tier per profile (a
productivity profile might intentionally use Accessibility for stability even if Root is
available, since Root's uinput path requires no reboot-persistent service but Accessibility
survives Magisk hiding changes better on some devices). The engine *does* auto-**downgrade** at
runtime if a chosen tier becomes unavailable mid-session, posting a non-intrusive notification
and logging the event.

## 8. Service Architecture (`:service`)

- `TitanInputService` — a `Service` (not bound to any Activity) started in foreground
  (`startForeground` with a low-priority, user-visible "Titan Mapper active" notification,
  required by Android 8+ background execution limits and by Android 14's foreground service
  type declarations — we declare `FOREGROUND_SERVICE_SPECIAL_USE` with justification string, as
  there is no closer-fitting FGS type for HID remapping). Hosts the `InputEventBus` singleton
  (via Hilt `@ServiceScoped`/custom `@Singleton` component) and owns the lifecycle of whichever
  tier's capture adapter is active.
- `TitanAccessibilityService` — separate from the above; Android requires Accessibility
  Services to be their own manifest-declared component. It forwards captured key events into
  the same `InputEventBus` via a Hilt entry point (`EntryPointAccessors`, since
  `AccessibilityService` isn't constructor-injectable by the framework).
- `OverlayEditorService` — a lighter-weight `TYPE_APPLICATION_OVERLAY` window service, only
  running while the user is actively editing a layout, destroyed otherwise (battery discipline).
- **Crash recovery**: `TitanInputService` is wrapped by a `ProcessLifecycleOwner`-aware
  watchdog in `:app` that listens for the service's `Binder.linkToDeath` and restarts it with
  `WorkManager`'s `ExistingWorkPolicy.REPLACE` one-shot (not a tight restart loop — exponential
  backoff capped at 30s to avoid battery-drain crash loops), persisting the last-known-good
  profile id so the restarted service resumes identical behavior.

## 9. UI Architecture

- **MVVM** throughout: every screen is `@Composable fun XScreen(viewModel: XViewModel = hiltViewModel())`.
  ViewModels expose a single `StateFlow<XUiState>` (immutable data class) plus a
  `fun onEvent(event: XUiEvent)` sealed-class intent function — no scattered boolean flags.
- **Material 3** with a custom `TitanTheme` (`:core:ui/theme`) providing a "desktop-inspired"
  dense layout mode (`LocalDensityMode` composition local) that tightens padding/touch targets
  when a physical pointer is detected (`InputDevice` hover events switch the whole app into a
  cursor-optimized density on the fly — hover states on buttons, right-click context menus via
  `onContextClick`).
- **Overlay Editor** is a hybrid: the editing *canvas* is Compose rendered inside the
  `OverlayEditorService`'s window (Compose-in-a-Service via `ComposeView` + manually managed
  `ViewTreeLifecycleOwner`/`ViewTreeSavedStateRegistryOwner`/`ViewTreeViewModelStoreOwner`,
  required boilerplate for Compose outside an Activity), with drag/resize gesture handling
  (`pointerInput` + `detectDragGestures`), snap-to-grid math in a pure Kotlin
  `GridSnapCalculator` (unit-testable without Compose).

## 10. Macro Engine

- `MacroExecutor` runs each macro as a coroutine on a dedicated single-thread
  `Dispatchers.Default.limitedParallelism(1)` context *per concurrently-running macro slot* (a
  configurable max-concurrent-macros limit, default 4) so one long macro never starves input
  processing.
- Step types: `PressKey`, `ReleaseKey`, `Delay(ms)`, `LoopStart(count|infinite)`, `LoopEnd`,
  `ConditionalIf(condition: MacroCondition, thenSteps, elseSteps)` where `MacroCondition`
  covers "if app X is foreground", "if toggle Y is active", "if device Z connected" — enough
  for real conditional logic without a full scripting language (deliberately *not* embedding a
  Lua/JS engine for v1 — security + binary size tradeoff documented in Section 15).
- **Recorder**: subscribes to the same `InputEventBus` as the engine, timestamps deltas between
  consecutive events, and on stop, run-length-encodes near-identical delays into a clean step
  list (so a human recording doesn't produce 40 nearly-identical 16ms delay steps).

## 11. Performance & Battery Strategy

- Capture adapters allocate **zero objects per event** on the hot path: `RawInputEvent` is a
  value-class-friendly data class pooled via a simple ring-buffer object pool for Tier 4's
  high-frequency JNI callback path (gaming mice at 1000Hz = 1000 allocations/sec avoided).
- `InputEventBus` uses `SharedFlow`, not `Channel`, specifically because we want **broadcast to
  multiple collectors** (Engine + EventLogger + LatencyMonitor simultaneously) without each
  collector needing its own capture hook.
- Background tiers that are expensive when idle (Shizuku Binder keep-alive, root shell session)
  are lazily torn down after a configurable idle timeout (default 10 min of zero events) and
  re-established on next event — most of a "long gaming session" is still active input, so this
  mainly saves battery during idle productivity use.
- `Doze`/`App Standby` exemption is requested **only** for the foreground-service window (which
  Android already exempts while FGS is running) — we deliberately do **not** request
  `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` globally, instead guiding OEM-specific autostart
  whitelisting only where the chosen tier actually requires it (Accessibility services on
  aggressive OEM skins).

## 12. Security Model

- Profile JSON exports are HMAC-signed (Keystore-backed key, `AndroidKeyStore` provider,
  non-exportable) — tampering with a shared `.titanprofile` file is detected before import.
- Root/Shizuku command surfaces are an explicit allow-list (`RootCommandWhitelist`) — we never
  build an arbitrary shell string from user input; macro "run shell command" (an advanced,
  off-by-default feature) goes through a sandboxed, user-confirmed, single-command executor
  with no shell metacharacter interpretation (`ProcessBuilder` with an argv array, not
  `sh -c`).
- No analytics/telemetry SDK by default; crash reports are **local-first** (written to app
  storage, user-initiated share via `ACTION_SEND`), respecting the "user data protection"
  requirement without assuming cloud telemetry consent.
- All IPC with the Shizuku privileged process uses Shizuku's own permission-scoped API — Titan
  Mapper never requests broader shell access than `INJECT_EVENTS`-equivalent operations need.

## 13. Compatibility Matrix (Android 12–16)

| Android Version | Relevant change | Titan Mapper handling |
|---|---|---|
| 12 (API 31) | Hidden-API restrictions reach max enforcement tier for non-system apps | Shizuku/Root tiers bypass via privileged UID context; Standard/Accessibility unaffected (no hidden API use) |
| 13 (API 33) | Per-app language/notification permission; stricter FGS types | `POST_NOTIFICATIONS` runtime request flow added; FGS type `specialUse` declared |
| 14 (API 34) | Mandatory FGS type declaration; `BAL` (Background Activity Launch) restrictions | Declared type + justification string; macro "launch app" action uses `PendingIntent` with proper `BAL` opt-in flags, not raw `startActivity` from background |
| 15 (API 35) | Further restrictions on `SYSTEM_ALERT_WINDOW` edge-to-edge, predictive back default-on | Overlay editor adopts `OnBackInvokedCallback`; edge-to-edge handled via `WindowInsets` in all Compose screens |
| 16 (API 36) | Continued 16KB page-size alignment requirement for native libs | Native (`:service` JNI for Tier 4) built with 16KB-aligned `.so` via updated NDK `cmake`/`ld` flags |

## 14. Plugin Architecture

- A plugin is an installed APK exposing a `ContentProvider` implementing the
  `com.titanmapper.plugin.MappingActionProviderContract` (a stable, versioned AIDL-free
  contract using simple `Bundle`/URI calls to avoid AIDL version skew). Plugins can contribute
  new `MappedAction` types (e.g., a "send MQTT message" smart-home action) discovered via
  `PackageManager.queryIntentContentProviders` with a well-known intent action
  (`com.titanmapper.action.PLUGIN_PROVIDER`), sandboxed so a misbehaving plugin can only fail
  its own action resolution, never crash the core input pipeline (all plugin calls wrapped in
  `runCatching` with a timeout `withTimeoutOrNull`).

## 15. Explicit Tradeoffs & Limitations (stated up front, not hidden in UI)

1. **Standard Mode cannot remap input system-wide.** This is an intentional Android sandboxing
   boundary, not a bug. We present it as "configure & test here, use Accessibility/Shizuku/Root
   to apply elsewhere."
2. **Accessibility Mode cannot inject raw key events into other apps**, only synthetic gestures
   — so "remap key A to key B inside another app" is not possible on this tier; only
   "key → touch gesture" mappings work system-wide here. We will not misrepresent this in copy.
3. **Shizuku requires a one-time (or per-reboot, depending on OEM/Android version) activation
   step by the user outside our app** (`adb` pair or a rooted starter) — we cannot eliminate
   this since Shizuku itself cannot self-elevate without one of those.
4. **Root Mode's exclusive `EVIOCGRAB`** can, if misused, make the physical keyboard/mouse stop
   working for the *entire OS* (since Android itself no longer sees the raw device) if Titan
   Mapper crashes before releasing the grab — mitigated by a native-side `atexit`/signal
   handler that force-ungrabs, plus a watchdog that ungrabs if the main process dies, but this
   residual risk is disclosed in the Root Mode opt-in dialog.
5. **No scripting language in Macros v1** (by design) — conditional macros cover common cases;
   a full embedded interpreter is deferred pending a sandboxing design that doesn't regress the
   security model in Section 12.
6. **Cloud backup requires the user's own Google Drive (or local file) — there is no
   Titan-Mapper-operated server**, by design, to avoid being a data custodian and to keep the
   app functional fully offline.
