# Testing Conventions & Instrumentation Budget

This document defines the engineering standards for JVM unit tests (`app/src/test/`) and Android instrumentation tests (`app/src/androidTest/`). Every rule is a checkable requirement for both implementation and code review.

The rules apply to every new or modified test. Existing tests that predate a rule are brought into line when their area is migrated; a change that touches such a test fixes the violations in the code it changes.

---

## 1. Test Tier Placement (Push Down the Pyramid)

Place every assertion in the lowest tier that proves the behavior. Never duplicate unit-testable logic or parameter matrices in instrumentation tests.

| Tier | Location | Owns | Must Not Own |
| :--- | :--- | :--- | :--- |
| **JVM Unit** *(default)* | `app/src/test/` | Pure logic, layout/geometry math (`calculateBookmarkGridLayout`), formatting, parsing, classification, ViewModel/Store/Flow state machines with fakes, AICore fallbacks, cache/producer lifecycle re-entry | Android Window/Canvas rendering or real SQLite migrations |
| **In-Memory Room** | `app/src/androidTest/.../db/` | DAO SQL, `@Transaction` atomicity, `instr()` bilingual search, Paging sources, Room schema migrations (`Room.inMemoryDatabaseBuilder`) | UI rendering, Activities, or shared on-disk app DB |
| **UI Instrumentation** *(narrowest)* | `app/src/androidTest/` | Real Compose layout with `WindowInsets`, IME, Accessibility/Semantics, `NavHost` transitions, configuration-change recreation, hardware/blur/pixel rendering | Pure math, exhaustive theme/locale matrices, or live AICore/network calls |

---

## 2. JVM Unit Test Standards

- **Fast, Deterministic, Offline**: Inject `CoroutineDispatcher`, clocks, and fake stores. Never use real wall-clock sleeps, live network calls, or live AICore/Gemini Nano inference (AICore is unavailable on emulators and CI).
- **Test Contracts & Failure Paths**: Verify observable state and persisted outcomes across happy, boundary, error, cancellation, and retry paths rather than private internals.
- **Symmetric Producer Lifecycle Re-Entry**: For reactive cache/state producers, test both initial hydration (`pending -> resolved`) and re-entry after eviction, invalidation, or generation reset (`resolved -> pending -> reloaded or fallback`) symmetrically across sibling flows.
- **JVM-Safe Singleton Guards**: When touching singletons backed by lazy Android dependencies (e.g., `AppConfig` -> `TidiloApplication.instance`), verify they stay safe on the host JVM without requiring Android framework initialization.
- **Assumptions Only for Host Limits**: Use `Assume` only to skip a case the current host cannot run (for example, symbolic links on a host that does not permit them) when CI runs that case on a host that can. Never use it to skip a test no environment runs.

---

## 3. Room & Persistence Testing

- **Prefer In-Memory Databases**: Use `Room.inMemoryDatabaseBuilder` for DAO, repository, and Paging data tests. Reserve `AppDatabase.getInstance` strictly for end-to-end UI integration tests that exercise the running app's database.
- **Migration Synchronization**: Whenever incrementing `AppDatabase` version or adding/modifying a `Migration`, update every migration test builder across `app/src/androidTest/` (audit `addMigrations(`, `sql.version =`, legacy version assertions, and `fingerprint(`). Rewind each pre-migration fixture cleanly to the exact legacy schema and columns before setting `sql.version`, map renamed columns before calling `fingerprint()`, and extract shared SQL rewind helpers into a single `internal` test helper instead of duplicating SQL across files.

---

## 4. UI Instrumentation: Render, Blur, Animation, & Screenshots

Instrumentation tests must pass reliably on **software-GPU CI emulators** (`-gpu guest`) as well as local hardware-accelerated AVDs.

- **Disable Blur & Dynamic Color by Default**: Every UI test that does not explicitly verify Haze backdrop blur, lighting, or Material You dynamic colors must render with `TidiloTheme(blurEnabled = false, dynamicColor = false)`. Tests launching `MainActivity` must set `AppConfig.setOverlayBlurEnabled(false)` before launch and restore the prior value in `finally`.
- **Keep Fixtures Free of Infinite Animations**: Use short fixture titles and labels so `Modifier.basicMarquee` and other infinite animations do not trigger during assertions. Never leave continuous transitions running across waits or synchronization checks.
- **No Debug Screenshots**: Never commit `host.capture(...)` or debug PNG dumps. Reserve `uiAutomation.takeScreenshot()` strictly for pixel-level rendering assertions (blur, lighting, surface drawing) and recycle every `Bitmap` in `finally`. Visual-review catalogues and benchmarks are not part of the test suite (see §8).
- **Pixel Tolerances for Software Rendering**: Pixel assertions use tolerances that hold on software-GPU rendering. A check that cannot pass there moves to a JVM test of the underlying math or is removed; never skip it per device.
- **Exclude System Bars & Cutouts from Pixel Samples**: Under edge-to-edge (`targetSdk 36`), `uiAutomation.takeScreenshot()` captures OS status/navigation bar icons and display cutouts over the app window. Keep sampled coordinates strictly inside runtime `WindowInsets` (`systemBars.union(displayCutout)` / `safeDrawing`)—either by padding the sampled container with those insets or clamping coordinates against them—never by screen-edge fractions or OS-version branches.
- **Pixel Capture Budget**: Assert pixels in memory (crop and inspect the `Bitmap`); never PNG-encode or write a capture to disk to assert it. Keep each `@Test` to about **20 captures** at most.

---

## 5. UI Instrumentation: Host, Orientation, Matrices, & Class Budget

- **Standard Compose Test Host for Isolated UI Tests**: Standardize isolated composable and screen tests on the v2 test APIs (`androidx.compose.ui:ui-test-junit4` + `ui-test-manifest` via Compose BOM): `androidx.compose.ui.test.junit4.v2.createComposeRule()`, `createAndroidComposeRule<A>()` when the test needs a specific Activity, and `createEmptyComposeRule()` with `ActivityScenario`. The v1 rules in `androidx.compose.ui.test.junit4` are deprecated; do not add new uses. Never launch full `MainActivity` (which mounts `TidiloApp`, `NavHost`, `TidiloBackground`, and ViewModels) only to immediately overwrite it with `setContent`. Reserve `MainActivity` strictly for real app startup, `NavHost` routes, intent/share handling, or full-app lifecycle tests.
  - *Coroutines in UI tests*: A test body that suspends uses `androidx.compose.ui.test.v2.runComposeUiTest { }`, which shares the Compose test clock with its `runTest` scope. Never combine a compose rule with a separate `runTest`; they keep separate schedulers.
  - *Input mode*: v2 starts each test in touch input mode. A test that moves focus with hardware keys (Tab, D-pad) sets keyboard input mode explicitly through the test configuration.
  - *Single `setContent` per `@Test`*: `createComposeRule().setContent` may be called only once per `@Test` method (calling it twice throws `IllegalStateException`). Vary inputs within a test by updating a `mutableStateOf` holder rather than re-calling `setContent` or multiplying Activity launches across trivial `@Test` methods. Assert after each state change before making the next (consecutive writes collapse into one frame, so intermediate states go unverified), and keep each `@Test` to **2–5** representative states.
- **Isolate `requestedOrientation` and `Activity.recreate()`**: Real orientation changes and `recreate()` belong exclusively in dedicated configuration-change / state-restoration tests—at most **1 round-trip per `@Test`**, isolated in its own test class. Verify landscape, narrow-width, and large-font layouts via explicit container bounds (`Box(Modifier.size(...))`) and `LocalDensity` rather than rotating SurfaceFlinger.
- **No Combinatorial Nesting**: Never nest loops over `Destination × AppTheme.entries × Light/Dark × Locale/Blur` inside one `@Test`.
  - Functional, layout, and scroll tests run in **one** theme and appearance (`AppTheme.DEFAULT`, light).
  - Theme and dark-mode parity checks run on lightweight isolated surfaces or separate focused tests—never combined with full-screen Paging or multi-destination loops.
- **Per-Class Runtime Budget**: Keep each instrumentation test class single-purpose and fast enough to complete well under **60 seconds** on software-GPU CI and local emulators. Split heavy concerns (rotation, Paging append, multi-screen flows) into separate classes so runners isolate processes cleanly.

---

## 6. UI Instrumentation: Synchronization, Semantics, & Scrolling

- **Wait by Cost Tier**: Choose the wait by what each check costs. Bound every wait with `withTimeout` (or the `waitUntil` timeout).

  | Tier | Checks | Rule |
  | :--- | :--- | :--- |
  | **Cheap** | In-memory values (`StateFlow`, atomics, ViewModel state), `ComposeTestRule` semantics | Prefer `StateFlow.first { }` or `waitUntil`; short polling intervals are allowed. |
  | **Medium** | Room/DB and file reads, legacy main-thread semantics traversals | Prefer suspending on `Flow.first { }` over polling. When polling is unavoidable, use **≥100ms** intervals and read DB off the main thread. |
  | **Heavy** | `takeScreenshot()`, `PixelCopy`, `captureToImage()`, bitmap encoding | Never inside a `waitUntil` condition. Capture only after `waitForIdle()`, at **≥100ms** intervals, at most **10** samples per assertion. |

  Never call `ActivityScenario.onActivity` (which invokes `waitForIdleSync()`) inside a polling loop.
- **Synchronize Before Reading State (v2)**: The v2 test APIs queue coroutines launched from composition (`LaunchedEffect`, `rememberCoroutineScope().launch`) on `StandardTestDispatcher` instead of running them immediately. After triggering work, synchronize before reading state: `waitForIdle()`, `runOnIdle { }`, or a semantics assertion or action (which waits for idle first). With `mainClock.autoAdvance = false`, run queued work with `mainClock.scheduler.runCurrent()` or step time with `advanceTimeByFrame()` / `advanceTimeBy()`.
- **Idle Is Not Settled for Platform Animations**: `waitForIdle()` covers Compose, not IME, window-inset, or system-bar animations. After showing or hiding the IME or changing system bars, wait for the concrete layout condition (for example, `waitUntil { viewport.bottom >= decorViewHeight - 1f }`).
- **Window Focus Before the IME**: Wait until `activity.hasWindowFocus()` before requesting the software keyboard; the IME ignores show requests from a window without focus.
- **No Fixed Settle Delays**: Never add fixed `delay`/`Thread.sleep`/`SystemClock.sleep` "to let the UI settle"; wait on idle sync plus a concrete condition instead. Fixed spacing is allowed only between injected input events that must produce a gesture velocity (e.g., `MotionEvent` moves).
- **Operate via Compose Semantics**: Drive interactions through Compose semantics actions (`performClick`, `performTextReplacement`, `performScrollToIndex`, or `SemanticsActions.*`) rather than raw screen coordinates.
- **Linear Semantics Traversal ($O(N)$)**: In legacy custom semantics traversals before migration, append into a single `MutableList` (never recursive `listOf(node) + flatMap`), index expected text in a `Map`/`Set` instead of nested `items × nodes` scans, and keep `includeOtherWindows = false` unless inspecting a Dialog, BottomSheet, or Popup.
- **Synchronized Scrolling & Animation Sampling**: Prefer `performScrollToIndex` / `SemanticsActions.ScrollToIndex` over repeated `ScrollBy`. When incremental scrolling is required (e.g., Paging append or header hide-on-scroll), wait for frame/state settlement or item-count advancement between steps instead of firing fixed short-interval scroll loops. Sample intermediate animation frames on the UI thread via `Choreographer.FrameCallback` (removing callbacks in `finally`), not cross-thread polling.
- **Virtual Time for Intermediate Frames**: When a test asserts an intermediate animation frame, step virtual time: `mainClock.autoAdvance = false` with `advanceTimeBy`/`advanceTimeByFrame` under `ComposeTestRule`, or an injected `BroadcastFrameClock` in legacy hosts. While the clock is paused, `waitForIdle()` neither advances time nor applies pending recomposition, so call `advanceTimeByFrame()` after each state change. Tests that assert only end states keep the default `autoAdvance = true`. Set `MotionDurationScale` only to `0f` (settle instantly) or `1f`; never slow motion with `scaleFactor > 1` and wait in real time to catch a frame.

---

## 7. Fixtures, Database, & Device-State Isolation

- **Minimal Fixture Size**: Seed only the rows the test observes (e.g., 1–4 items for card, detail, or layout checks; larger datasets only in dedicated Paging-append tests).
- **Batched DB Setup & Cleanup**: Never insert or delete fixture rows inside single-row `forEach` DAO loops. Use bulk DAO methods, a single `@Transaction`, or a scoped batch `DELETE` (`WHERE id IN (...)` or unique prefix).
- **Offline Visual-Cache Stubs & Tiny Bitmaps**: Because `TidiloTheme` binds `SingletonCacheVisualAdapter`, UI tests rendering bookmark or feed URLs must stub `OgImageManager` and `DomainIconManager` offline (restored in `finally`) or inject a fake `LocalCacheVisualSource`. Use tiny synthetic bitmaps (e.g., `4×4`–`38×20`) instead of allocating and PNG-compressing full-resolution `1200×630` images.
- **Deterministic Shared-Device Cleanup**:
  - Tag shared-DB fixture rows with a unique test ID/prefix and delete exactly those rows in `withContext(NonCancellable + Dispatchers.IO)` inside `finally`.
  - Release everything a test starts in `finally`: `finish()` launched Activities or `close()` scenarios, `cancel()` custom `Recomposer`s and their `CoroutineScope`s, remove `Choreographer` callbacks, and `recycle()` bitmaps. Leaks accumulate across tests in one instrumentation process.
  - Read and restore every modified `SharedPreferences` entry, `AppConfig` flag, and existing test hook (`DomainIconManager`, `OgImageManager`; see §9) in `finally`.
  - Pass an explicit temporary directory to file-backed stores (verified against `applicationContext` path resolution when using `ContextWrapper`) and delete it in `finally`.

---

## 8. Order, Shard, & Environment Independence

- **Any Order, Any Shard**: Every `@Test` passes alone, in any order, and when `numShards` places its class's methods in different processes. No test depends on another test's side effects, `@FixMethodOrder`, or a multi-process protocol.
- **Set Up the State the Test Depends On**: A test that depends on device or process state establishes it itself and restores what it changed in `finally`:
  - Grant runtime permissions with `uiAutomation.grantRuntimePermission(packageName, permission)`. Granting does not restart the app process. `pm revoke` and `revokeRuntimePermission` kill the app process, which hosts the instrumentation; a test that needs a denied state revokes without killing the process, as `NotificationPermissionColdLaunchTest` does.
  - Reset WorkManager with `WorkManagerTestInitHelper` and start from known `AppConfig` / `SharedPreferences` values.
- **No Assumptions or Opt-Ins in androidTest**: Never use `Assume` or instrumentation-argument opt-ins in `app/src/androidTest/`. The test report counts assumption failures as failures, and an opt-in test does not run in the regular suite. Fix the precondition (grant, reset, inject) or move the check to the JVM tier. Visual-review catalogues and benchmarks live outside the regular suite.
- **Device Independence**: Never assert counts or positions that depend on font metrics, screen size, or density (for example, "exactly one badge is visible"). Assert presence (`fetchSemanticsNodes().isNotEmpty()`), relative order, or bounds inside an explicit container (`Box(Modifier.size(...))`).
- **Animation Independence**: Tests pass with system animations disabled (as on CI) and at scale `1.0` (the device default). A test that asserts intermediate frames controls time itself (`mainClock`, `MotionDurationScale`) instead of relying on the device animation scale; a test that asserts an end state waits for that end state.
- **System-Originated Intents**: When a test stands in for the system, such as a notification tap that System UI sends, it sends the `PendingIntent` with the privilege the system has. Adopt `START_ACTIVITIES_FROM_BACKGROUND` through `uiAutomation.adoptShellPermissionIdentity(...)` for the `send()` call only, drop it in `finally`, and pass `ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)`. A process without a visible window cannot otherwise start an activity on API 34+.
- **Reference Environment**: Every test passes on API 34 (`minSdk`) and API 36 (`targetSdk`), on software-GPU (`-gpu guest`) and host-GPU emulators, with animations disabled and enabled, at font scale `1.0`.

---

## 9. Test Seams

- **Inject Fakes**: New test seams are constructor parameters, factory arguments, or `CompositionLocal` providers that tests fill with fakes.
- **No New Process-Wide Hooks**: Do not add process-wide mutable test hooks (for example, an `internal var` lambda on an `object`) to production code. They are shared by every test in the instrumentation process, and production reads of `@VisibleForTesting` members are reported by lint.
- **Existing Hooks**: `DomainIconManager.httpDownloader` / `htmlDiscoverer`, `OgImageManager.imageDownloader`, and the `ImportRepository` transaction hooks remain until their area is migrated to injected fakes. A test that sets one restores it in `finally`.

---

## 10. Warnings

- **No Deprecated APIs in New or Modified Test Code**: New and modified code in `app/src/test/` and `app/src/androidTest/` compiles without deprecation warnings. Replace a deprecated API in the code you change instead of suppressing the warning.
