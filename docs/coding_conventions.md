# Coding Conventions & Best Practices

This document defines the strict engineering standards, naming conventions, Compose performance guidelines, and coroutine practices required for the Tidilo codebase.

---

## 1. Naming & Formatting Conventions

### 1.1 Identifiers
- **Classes, Objects, Interfaces, Enums**: `PascalCase` (e.g., `BookmarkRepository`, `QueueItemStatus`).
- **Composable Functions**: `PascalCase` matching UI components (e.g., `BookmarkItemCard`, `LibraryScreen`).
- **Standard Functions & Methods**: `camelCase` (e.g., `filterBookmarks`, `addBookmark`).
- **Properties & Variables**: `camelCase` (e.g., `filteredBookmarks`, `isBarsVisible`).
- **Constants**: `SCREAMING_SNAKE_CASE` (e.g., `DEFAULT_TAG`, `TIMEOUT_MS`).
- **Backing Properties**: Leading underscore for private state flows (e.g., `private val _bookmarks = MutableStateFlow(...)`, `val bookmarks: StateFlow<...> = _bookmarks.asStateFlow()`).

### 1.2 File Naming
- Each file must match the primary class or composable defined within it (e.g., `LibraryScreen.kt`, `BookmarkRepository.kt`).
- Small, tightly coupled helper composables (e.g., `CompactTagBadge`) may reside in the same file as their parent widget (`BookmarkCard.kt`).

---

## 2. Jetpack Compose Best Practices

### 2.1 State Observation & `CompositionLocal` Scoping
- **Always use `collectAsStateWithLifecycle()`**: Never use standard `collectAsState()` for observing ViewModel or global state flows in UI composables. `collectAsStateWithLifecycle()` automatically cancels flow collection when the app is in the background, conserving battery and system memory.
- **State Hoisting**: Keep reusable components stateless. Pass data models and event callbacks (`() -> Unit` or `(T) -> Unit`) rather than passing ViewModel instances down the widget tree.
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

### 2.4 Paging 3 Load State & Skeleton Placeholders
- **Distinguish Same-Key Invalidation from Changed Query/Filter Keys**: When observing `LazyPagingItems`, never replace the rendered list or grid with a full-screen skeleton or loading indicator solely on `loadState.refresh is LoadState.Loading`.
  - **Same-Key Room Invalidations**: When database updates (e.g., background RSS direct ingestion, bookmark tagging, or batch writes) invalidate Room's `PagingSource` under the same active filter or search query, `loadState.refresh` transitions to `Loading` while existing items remain in memory (`itemCount > 0`). In this case, keep the rendered list mounted and do not trigger placeholders, allowing Paging 3 to diff and update items seamlessly in place.
  - **Changed Query / Filter Keys & Presentation Synchronization**: Search exports a unified `PagingGeneration(key, flow)` StateFlow and collects its matching flow. Library collects an immutable domain/feed/mode Pager inside each animated destination, with session leases keeping outgoing cache scopes alive through exit. Loading/error/empty branches belong inside the transition host, so loading never disposes the navigation animation. Keep same-key Room invalidations mounted when items remain. Key library destination caches by NSFW visibility so changing visibility immediately disposes stale content.
  - **Generation Cache Scoping & Cancellation**: Never cache transient Pager flows directly in unconstrained `viewModelScope`. Search cancels its previous dedicated generation scope when replacing it. Library retains one selected session in a dedicated child scope across detail navigation; AnimatedContent compositions lease their sessions through DisposableEffect. Nonselected sessions cancel when their last exit is disposed. A SideEffect reactivates a selected session even when a rapid A-to-B-to-A transition reuses its composition. Replacing an unused retained session cancels its scope. Do not cancel an outgoing library Pager merely because global selection changed, and do not retain an unbounded map of per-filter scopes.

---

## 3. Coroutines, Threading, Flow Sharing, & Database Practices

### 3.1 Dispatchers & Main-Thread I/O Prohibition
- **`Dispatchers.Main`**: Strictly reserved for UI rendering, event forwarding, and state mutations.
- **`Dispatchers.IO`**: Required for all disk I/O, directory traversal (`CacheStorageRepository.scan`, `File.walkTopDown`), `SharedPreferences` serialization/deserialization, Jsoup network fetching, and DOM parsing. Never run synchronous filesystem `stat`/read calls directly on `viewModelScope.launch` (`Dispatchers.Main.immediate`); always wrap them in `withContext(Dispatchers.IO)`.
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

---

## 4. Static Allocations, HTML Parsing, Build & Privacy Hygiene

### 4.1 Single-Pass HTML DOM Parsing & Safe Mutation Ordering
- **Parse Once per URL**: Never parse the same HTML payload into a Jsoup `Document` multiple times during URL ingestion (`WebPageParser.kt`, `DomainIconResolver.kt`).
- **Respect Destructive DOM Mutators**: Because `Readability4J.parse()` and `extractJsoupCleanText()` destructively remove elements from the Jsoup `Document`, execute operations in strict order:
  1. Run non-destructive metadata and icon candidate extraction (`DomainIconResolver.parseHtml(doc, finalUrl)`) on `doc` first.
  2. Pass `doc.clone()` to Readability4J to preserve the original debug extraction.
  3. Remove common ad blocks, including `af_box`, from the body using the shared filter, preserving structural content roots. Extract Readability from a fresh clone only if blocks were removed and text remains; otherwise reuse the original extraction or retain an empty result.
  4. Run `extractJsoupCleanText(doc)` last on `doc` directly without an extra clone. Store the filtered Readability/Jsoup choice separately for production and debug body inspection.
- **Avoid Byte Array Re-Encoding**: Measure raw HTML payload size via Jsoup's `response.bodyAsBytes().size` instead of calling `rawHtml.toByteArray(Charsets.UTF_8).size`.

### 4.2 Hoisting `Regex`, `DateTimeFormatter`, and Enum Lookup Maps
- **Pre-Compiled Regular Expressions**: Never instantiate `Regex(...)` or `"pattern".toRegex()` inside `@Composable` functions, loops, or hot parsing paths. Always declare fixed patterns as top-level or `companion object` `private val` constants (`SCREAMING_SNAKE_CASE`).
- **Shared Thread-Safe Date Formatters**: Never instantiate `SimpleDateFormat` or `DateTimeFormatter.ofPattern(...)` inside `@Composable` loops or per-call utility functions. Hoist `java.time.format.DateTimeFormatter` instances to static constants and resolve dynamic `ZoneId.systemDefault()` and active `Locale` at format time.
- **Constant-Time Enum Resolution**: Use pre-built case-insensitive lookup `Map`s in enum `companion object`s (`Category.fromId`, `Subcategory.fromId` with `.trim().lowercase()`) instead of linear `entries.firstOrNull` scans.

### 4.3 Release Build Shrinking & Offline-First Backup Policy
- **R8 Minification & Resource Shrinking**: Keep `isMinifyEnabled = true` and `isShrinkResources = true` enabled in `buildTypes.release` (`app/build.gradle.kts`). In `proguard-rules.pro`, avoid broad package-wide `-keep class org.jsoup.** { *; }` rules on non-reflective libraries so R8 tree-shaking can strip unused classes and `material-icons-extended` vectors.
- **Strict Offline-First Backup Hardening**: Keep `android:allowBackup="false"` in `AndroidManifest.xml` so private local SQLite databases (`bookmarks`, `import_tasks`, `rss_feeds`) and NSFW policies are never uploaded to unencrypted cloud backups.

---

## 5. Error Handling & Defensive Coding

1. **Defensive Parsing**:
   - Always wrap JSON parsing, URI parsing, and date parsing with `runCatching { ... }.getOrElse { ... }` or `getOrDefault(...)`.
   - Never allow a malformed HTTP response or corrupted cache file to crash the app.

2. **Strict HTTP Validation**:
   - The AI tagging pipeline requires HTTP 200 responses. Non-200 responses (e.g., HTTP 404, 500) must throw explicit exceptions that route items to the `Error` group with human-readable error messages.

3. **AICore Failure Handling**:
   - Catch specific exceptions (`RESOURCE_EXHAUSTED`, `UNAVAILABLE`) and categorize them cleanly into system busy states or fallback responses.

4. **Preserving Code Integrity**:
   - Maintain all existing comments and documentation unless explicitly modifying or refactoring that specific feature.
   - Do not remove fallback mechanisms or sample data repair routines.

---

## 6. Git Commit Workflow & Rules

### 6.1 Mandatory Commit for Every Instruction
- **Strict Rule**: Every AI agent and developer **MUST commit all changes upon completing each user instruction or task**.
- Never leave uncommitted changes at the end of an interaction turn.
- Before committing:
  1. Verify the build and tests pass (`./gradlew testDebugUnitTest`).
  2. Ensure documentation and relevant logs are up to date.
  3. Stage all modified and newly created files (`git add .`).

### 6.2 Conventional Commit Messages
- Use standard Conventional Commits format:
  - `feat(<scope>)`: New user-facing or architectural features.
  - `fix(<scope>)`: Bug fixes and behavioral repairs.
  - `perf(<scope>)`: Performance optimizations (recomposition, I/O dispatching, memory).
  - `refactor(<scope>)`: Code refactoring without behavior change.
  - `docs(<scope>)`: Creating or updating documentation and specifications.
  - `test(<scope>)`: Adding or updating unit/integration tests.
  - `chore(<scope>)`: Maintenance, Gradle scripts, configuration tweaks.
