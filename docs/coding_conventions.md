# Coding Conventions & Best Practices

This document defines the strict engineering standards, naming conventions, Compose performance guidelines, and coroutine practices required for the Tidilo codebase.

---

## 1. Naming & File Layout

- **Kotlin Identifiers & Backing Properties**: Follow standard Kotlin casing (`PascalCase` types and `@Composable` functions, `camelCase` functions and properties, `SCREAMING_SNAKE_CASE` constants), and prefix private mutable backing `StateFlow` properties with `_` (`private val _bookmarks = MutableStateFlow(...)`, `val bookmarks: StateFlow<...> = _bookmarks.asStateFlow()`).
- **File Naming**: Match each file to its primary class or composable (`LibraryScreen.kt`, `BookmarkRepository.kt`); small, tightly coupled helper composables (e.g., `CompactTagBadge`) may reside in their parent file (`BookmarkCard.kt`).

---

## 2. Jetpack Compose Best Practices

### 2.1 State Observation & `CompositionLocal` Scoping
- **Always use `collectAsStateWithLifecycle()`**: Never use standard `collectAsState()` for observing ViewModel or global state flows in UI composables. `collectAsStateWithLifecycle()` automatically cancels flow collection when the app is in the background, conserving battery and system memory.
- **State Hoisting & Producer Consolidation**: Keep reusable and row-level components stateless by passing immutable values and event callbacks (`() -> Unit` or `(T) -> Unit`) rather than ViewModel, repository, `DomainPolicyStore`, `CoroutineScope`, or `Context` instances (e.g., for `Toast` feedback). Additionally, when multiple UI consumers in the same screen or card depend on the same asynchronous visual key (e.g., domain artwork for a watermark and its dominant accent color for lighting), hoist a single state producer in the common parent scope and share the resolved immutable value object and already-read `CompositionLocal` instances downward instead of opening duplicate observers inside child helpers.
- **Compound Toggle & Selection Rows (Accessibility)**: For settings or list rows combining descriptive text with a trailing `Switch` or `Checkbox`, attach `Modifier.toggleable(value = ..., role = Role.Switch, onValueChange = ...)` (or `Role.Checkbox`) to the enclosing `Row` and set `onCheckedChange = null` on the trailing control. Never wire `onCheckedChange` solely to the trailing `Switch` when descriptive labels live in sibling `Text` nodes; the enclosing `Row` owns the semantics and full-width touch target.
- **Localize High-Frequency `CompositionLocal` Reads**: Never read frequently toggling `CompositionLocal` values (such as `LocalBarsVisible.current`) at the root of large screen composables (`LibraryScreen`, `SearchScreen`, `ImportScreen`), as this invalidates and recomposes the entire screen on every scroll direction change. Always encapsulate the read inside a dedicated, narrow child wrapper composable (e.g., `LibraryHeaderAnimatedVisibility`).

### 2.2 Lazy Layouts Optimization
- **Mandatory `key`**: Every `items(...)` call in `LazyColumn`, `LazyRow`, or `LazyVerticalGrid` must provide a unique, stable key (e.g., `key = { it.id }` or `bookmarks.itemKey { it.id }`).
- **Mandatory `contentType`**: Every lazy list must provide a `contentType` lambda (e.g., `contentType = { "bookmark_card" }`) to enable item composition recycling across scroll boundaries.
- **Fixed items**: Single `item { ... }` blocks inside lazy lists should also specify explicit keys and `contentType` values when mixed with dynamic sections.
- **Fast-Path In-Memory Cache Hits in Lazy Items**: When loading bitmaps or icons inside lazy cards (`produceState` in `BookmarkGridCard`, `rememberDomainIcon`), check the synchronous in-memory `LruCache` first (`cached != null`). When a memory cache hit occurs, skip launching `Dispatchers.IO` fetch coroutines and only subscribe to invalidation/update signals.
- **No SubcomposeLayout / Lazy Layouts inside Intrinsic Measurement Containers**: Never place `LazyColumn`, `LazyRow`, or other `SubcomposeLayout`-based composables inside containers that enforce intrinsic measurement (such as `DropdownMenu`, which applies `Modifier.width(IntrinsicSize.Max)`). Doing so throws `IllegalStateException: Asking for intrinsic measurements of SubcomposeLayout layouts is not supported`. Use `Column` with `Modifier.verticalScroll(rememberScrollState())` for popups and menus.

### 2.3 Recomposition, 3-Phase Optimization, & Draw Caching
- **Remember & Derived State**:
  - Use `remember(key1, key2) { ... }` for computationally non-trivial operations (e.g., list filtering/partitioning such as splitting `readyTasks` and `rssReadyTasks`, localized label resolution, or token parsing).
  - Use `derivedStateOf` when calculating a discrete boolean or enum state (e.g., swipe threshold crossed, scroll direction) from a high-frequency continuous source (e.g., `Animatable`, `LazyListState`).
- **Defer Continuous Animation Reads to Layout / Draw Phases**:
  - Never pass continuously animating `Dp` or `Float` values directly to value-based modifiers (❌ `Modifier.offset(x = animatedDp)`), and never read `Animatable.value` in a parent composition scope when only used for visual translation or drawing.
  - Retain `State<Dp>` / `State<Float>` or pass lambda providers (`() -> Float`) and read the value exclusively inside layout or draw lambdas:
    - ⭕ `Modifier.offset { IntOffset(animatedDpState.value.roundToPx(), 0) }`
    - ⭕ `Modifier.graphicsLayer { alpha = alphaProvider() }`
    - ⭕ `Modifier.drawBehind { ... }`
- **Cache Brushes & Shaders with `Modifier.drawWithCache`**:
  - Never allocate `Brush` instances (`Brush.linearGradient`, `Brush.verticalGradient`, `Brush.radialGradient`), `Shader`s, or color-stop arrays inside `Modifier.drawBehind`, which executes on every draw frame.
  - Always use `Modifier.drawWithCache { val brush = ...; onDrawBehind { drawRect(brush) } }` so gradient objects are allocated only when layout dimensions or color dependencies change, and hoist static curve tables (such as `SMOOTHERSTEP_CURVE`) to top-level constants.
- **Keyword Precomputation**: In `SearchScreen`, word tokens and tag tokens are parsed once at the screen level using `remember(searchQuery)` and passed down as immutable lists, completely eliminating per-item tokenization during scroll.

### 2.4 Paging 3 Load State & Scope Lifecycle
- **Same-Key Room Invalidations**: When observing `LazyPagingItems`, never replace a populated list or grid (`itemCount > 0`) with a full-screen skeleton solely because `loadState.refresh is LoadState.Loading`; let Paging 3 diff Room invalidations in place. Keep loading, error, and empty branches inside transition hosts so loading states never cancel navigation animations.
- **Generation & Session Scope Leases**: Never cache transient `Pager` flows in unconstrained `viewModelScope` or in an unbounded map of per-filter scopes. Bind Pager lifecycles to replaceable generation scopes or destination-owned leases (e.g., `DisposableEffect` exit disposal and `SideEffect` reactivation when returning via rapid transitions) and invalidate stale caches immediately on Private Content visibility changes. See [Library & Search UI §8](ui_library.md#8-paged-bookmark-results) for `Search` and `Library` behavior.

### 2.5 Responsive Chip Rows & Large-Font Tolerance
- **Wrap Multi-Badge & Localized Tag Rows with `FlowRow`**: Never lay out variable-length classification tags, localized category labels, and icon-prefixed status badges (such as `BookmarkTagSection`) inside a single-line `Row` without wrapping. Always use `FlowRow` with `itemVerticalAlignment = Alignment.CenterVertically` so badges stay single-line on standard screens while wrapping cleanly on narrow grid cards or `2f` system font scaling without right-edge clipping.

---

## 3. Coroutines, Threading, Flow Sharing, & Database Practices

### 3.1 Dispatchers & Main-Thread I/O Prohibition
- **`Dispatchers.Main`**: Strictly reserved for UI rendering, event forwarding, and state mutations.
- **`Dispatchers.IO`**: Required for all disk I/O, directory traversal (`CacheStorageRepository.scan`, `File.walkTopDown`), `SharedPreferences` serialization/deserialization, Jsoup network fetching, and DOM parsing. Never run synchronous filesystem `stat`/read calls directly on `viewModelScope.launch` (`Dispatchers.Main.immediate`); always wrap them in `withContext(Dispatchers.IO)`. `Context.cacheDir` and `Context.filesDir` perform underlying filesystem existence checks on Android; always resolve them inside `withContext(Dispatchers.IO)` and only after in-memory `LruCache` fast-path checks miss.
- **`Dispatchers.Default`**: Use for CPU-intensive background sorting, tokenization, or `Collator` comparisons.
- **`viewModelScope`**: Use for ViewModel-initiated user actions.
- **`repositoryScope`**: Use dedicated `CoroutineScope(Dispatchers.IO + SupervisorJob())` inside singleton repositories for independent background persistence tasks that must outlive any individual screen lifecycle.

### 3.2 Mutual Exclusion & Synchronization
- Sequential pipelines (such as processing URLs through Gemini Nano in `ImportRepository`) must be protected with a coroutine `Mutex`:
  ```kotlin
  val executionMutex = Mutex()
  repositoryScope.launch {
      executionMutex.withLock {
          // Process one item at a time through AICore
      }
  }
  ```
- When publishing monotonic counters to a `MutableStateFlow` or lazily initializing shared state from an `AtomicReference`, keep the state mutation and publication inside the same lock or atomic `update { maxOf(it, next) }` block so concurrent callers never observe partially initialized state or overwrite newer values with stale ones.
- **Non-Reverting Initial Hydration vs. Live Update Observers**: When a reactive state producer runs an asynchronous initial load/extraction concurrently with a live invalidation or cache-update observer, never allow a delayed initial completion to overwrite or revert a newer state already delivered by the observer. Serialize initial resolution and observer emissions under a coroutine `Mutex` (or track observer delivery and re-read latest cache state before emitting) so state progression remains strictly forward.
- **Symmetric Contracts Between Fast-Path Checks and Slow-Path Completion**: When a fast path requires a composite invariant (e.g., both cached primary artwork and derived dominant color) before bypassing background work, the slow/fallback path must explicitly guarantee that every member of that invariant is resolved before emitting final completion. Do not assume an upstream cache store computes derived attributes when returning an early hit on the primary asset.

### 3.3 StateFlow Sharing, Bounded Buffers, & Input Debouncing
- **Prefer `SharingStarted.WhileSubscribed(5000)` over `SharingStarted.Eagerly`**: In ViewModels, database-backed and sorting-heavy `StateFlow`s (e.g., `domainGroups`, `rssFeedGroups`, count observers) must use `SharingStarted.WhileSubscribed(5000)` so upstream Room queries and `Collator` sorting stop when the screen leaves the foreground.
- **Conditional Search Input Debouncing**: Text search streams driving Room `PagingSource`s or count queries must debounce rapid typing while responding immediately to input clearing:
  ```kotlin
  _searchQuery
      .debounce { if (it.isBlank()) 0L else 250L }
      .distinctUntilChanged()
  ```
- **Bounded Notification Streams Over Unbounded Per-Key Maps**: Never store unbounded per-item `MutableStateFlow` instances in a `ConcurrentHashMap` (which leaks memory over long sessions). Use a shared `MutableSharedFlow` with bounded fallback queueing and overflow reconciliation (e.g., `MAX_PENDING_FALLBACK_KEYS` + `RECONCILE_ALL_KEY` in `OgImageManager`).

### 3.4 Room DAO Batching, Entity Mapping, & Search Indexing
- **Chunked Bulk Operations in `@Transaction`**:
  - Never execute single-row SQLite statements inside a `forEach` loop in batch `@Transaction` methods (e.g., `BookmarkDao.insertAll`).
  - Always use bulk DAO statements (`insertRows(List<BookmarkEntity>)`, `deleteOccurrencesForBookmarks(List<String>)`, `insertOccurrences(List<BookmarkEntityOccurrence>)`) chunked in batches of **500** (`bookmarks.chunked(500)`) to avoid `SQLiteException: too many SQL variables` (SQLite's 999 bind parameter limit), deduplicating by primary key within each chunk (`chunk.associateBy { it.id }.values.toList()`).
- **Preserve Pre-Computed Entity Fields in UI Models**:
  - When `BookmarkEntity` persists pre-calculated columns (`domain`, `ogImageKey`), always pass them directly into `BookmarkItem` during `toBookmarkItem()`.
  - Never define expensive URL normalization or SHA-256 hashing inside a custom `get()` property on a UI data class or recompute them inside composable item cards.
- **Bilingual Substring Search (`instr`) vs. Default FTS**:
  - Retain SQLite `instr()` over normalized comparison columns (`searchTitle`, `searchDescription`, `searchUrl`, `searchValue`) for bilingual (Japanese/English) and arbitrary URL substring queries. Do not replace with default `@Fts4`/`FTS5` whitespace tokenizers (`simple`/`unicode61`) or trigram tokenizers without fallback, as they break unsegmented Japanese words and 1–2 character queries.
- **Room Schema Upgrades & Cross-Test Migration Synchronization**:
  - Whenever incrementing `AppDatabase` version, adding a `Migration`, or renaming/dropping columns or indexes, search `app/src/androidTest/` for all `addMigrations(`, `sql.version =`, previous version assertions (`assertEquals(<oldVersion>`), and `fingerprint(` usages across all test packages (including sibling suites such as `BookmarkDatabaseTest`, `CollectionDatabaseTest`, and `NotificationRoomTest`).
  - Register the new `Migration` in every test builder, rewind each pre-migration fixture cleanly to the exact legacy schema and columns before setting `sql.version`, map renamed columns before calling `fingerprint()`, and extract shared SQL rewind helpers into a single `internal` test helper instead of duplicating SQL statements across test files.

### 3.5 Lifecycle Re-Entry & Symmetric Producer Testing
- **Cover Full Lifecycle Cycles Across Sibling Producers**: For reactive cache/state producers, unit tests must exercise not only the forward `pending -> resolved` transition, but also lifecycle re-entry paths—post-resolution eviction, invalidation, and generation resets (`resolved -> pending -> reloaded or fallback`). Apply this coverage symmetrically across all sibling flows sharing the same visual or cache contract.

---

## 4. Static Allocations, HTML Parsing, Build & Privacy Hygiene

### 4.1 Single-Pass HTML DOM Parsing & Safe Mutation Ordering
- **Parse Once per URL**: Never parse the same HTML payload into a Jsoup `Document` multiple times during ingestion (`WebPageParser.kt`, `DomainIconResolver.kt`), and measure byte size via Jsoup's `response.bodyAsBytes().size` instead of `rawHtml.toByteArray(Charsets.UTF_8).size`.
- **Respect Destructive DOM Mutators**: Because `Readability4J.parse()` and `extractJsoupCleanText()` destructively strip elements from a Jsoup `Document`, always execute non-destructive metadata/icon extraction (`DomainIconResolver.parseHtml(doc, finalUrl)`) first, pass `doc.clone()` to Readability4J (re-running on a filtered clone only if ad-block filtering removes elements per [AICore §3](aicore.md#3-web-content-preprocessing-pipeline)), and run `extractJsoupCleanText(doc)` last on `doc` while storing production and debug body fields separately.

### 4.2 Hoisting `Regex`, `DateTimeFormatter`, and Enum Lookup Maps
- **Pre-Compiled Regular Expressions**: Never instantiate `Regex(...)` or `"pattern".toRegex()` inside `@Composable` functions, loops, or hot parsing paths. Always declare fixed patterns as top-level or `companion object` `private val` constants (`SCREAMING_SNAKE_CASE`).
- **Shared Thread-Safe Date Formatters**: Never instantiate `SimpleDateFormat` or `DateTimeFormatter.ofPattern(...)` inside `@Composable` loops or per-call utility functions. Hoist `java.time.format.DateTimeFormatter` instances to static constants and resolve dynamic `ZoneId.systemDefault()` and active `Locale` at format time.
- **Constant-Time Enum Resolution**: Use pre-built case-insensitive lookup `Map`s in enum `companion object`s (`Category.fromId`, `Subcategory.fromId` with `.trim().lowercase()`) instead of linear `entries.firstOrNull` scans.

### 4.3 Release Build Shrinking & Local-First Backup Policy
- **R8 Minification & Resource Shrinking**: Keep `isMinifyEnabled = true` and `isShrinkResources = true` enabled in `buildTypes.release` (`app/build.gradle.kts`). In `proguard-rules.pro`, avoid broad package-wide `-keep class org.jsoup.** { *; }` rules on non-reflective libraries so R8 tree-shaking can strip unused classes and `material-icons-extended` vectors.
- **Strict Local-First Backup Hardening**: Keep `android:allowBackup="false"` in `AndroidManifest.xml` so private local SQLite databases (`bookmarks`, `import_tasks`, `rss_feeds`) and domain Private policies are never uploaded to unencrypted cloud backups.

---

## 5. Error Handling & Defensive Coding

- **Defensive Parsing**: Wrap JSON, URI, and date parsing in `runCatching { ... }.getOrElse { ... }` or `getOrDefault(...)`; never allow malformed HTTP responses or corrupted cache files to crash the app.
- **Strict HTTP Validation**: The AI tagging pipeline requires HTTP 200; non-200 responses (e.g., 404, 500) must throw explicit exceptions routing items to the `Error` group with human-readable messages.
- **AICore Failure Handling**: Catch specific exceptions (`RESOURCE_EXHAUSTED`, `UNAVAILABLE`) and map them cleanly to system busy states or fallback responses (see [AICore §8](aicore.md#8-error-handling--rate-limiting)).
- **Preserving Code Integrity**: Retain unrelated comments, documentation, fallback mechanisms, and sample data repair routines.
- **Non-Negative Hashing & Cache Path Sanitization**: Mask hash codes with `hashCode() and 0x7FFFFFFF` (or `mod(n)`) instead of `kotlin.math.abs(hashCode())` to avoid `Int.MIN_VALUE` overflow, and sanitize external path segments against a strict allowlist regex before building `File(cacheDir, name)` paths.
- **Single Helper Boundary for Legacy Compatibility**: Encapsulate each backward-compatibility rule (legacy JSON keys, fields, or preference fallbacks) in one shared `internal` helper/extension (e.g., `JSONObject.readLegacyCompatiblePrivateFlag()`, `AppConfig.readBooleanPreferenceWithLegacyFallback(...)`) with focused unit tests instead of duplicating fallback checks across callers.
- **JVM-Safe Evaluation of Lazy Android Dependencies**: Singletons referenced in JVM unit tests (such as `AppConfig`) may hold `by lazy` properties backed by `TidiloApplication.instance`, which throw `UninitializedPropertyAccessException` outside Android. Keep an outer `runCatching` or deferred provider lambda around eager initialization sites passing lazy Android dependencies like `prefs`, and comment the guard so refactoring does not remove it.

---

## 6. Verification & Commit Conventions

### 6.1 Pre-Commit Quality Gates
See [Coding Workflow (Phase 2)](../.agents/coding/workflow.md) for branching, approval gates, and commit lifecycle rules. Before committing code changes:
- **Unit & Instrumentation Verification**: Ensure `./gradlew testDebugUnitTest` passes 100%. Whenever Room schemas, migrations, or UI navigation/semantics change, also assemble `assembleDebugAndroidTest` and run the affected `androidTest` suites on a connected device/emulator (`testDebugUnitTest` and compilation alone do not verify SQLite migrations or Compose runtime semantics).
- **Navigation Route & Doc Parity**: Update UI route assertions (such as `SettingsRedesignTest`) when adding or moving routes/settings entries, and keep `docs/ui_design.md` and screen UI specs (`ui_library.md`, `ui_bookmark_detail.md`, `ui_settings.md`) synchronized with the implementation.

### 6.2 Conventional Commit Messages
Use standard Conventional Commits in English:
- `feat(<scope>)`: New user-facing or architectural features.
- `fix(<scope>)`: Bug fixes and behavioral repairs.
- `perf(<scope>)`: Performance optimizations (recomposition, I/O dispatching, memory).
- `refactor(<scope>)`: Code refactoring without behavior change.
- `docs(<scope>)`: Documentation and specification changes.
- `test(<scope>)`: Unit or instrumentation tests.
- `chore(<scope>)`: Maintenance, Gradle scripts, or configuration tweaks.
