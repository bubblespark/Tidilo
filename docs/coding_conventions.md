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

### 2.1 State Observation
- **Always use `collectAsStateWithLifecycle()`**: Never use standard `collectAsState()` for observing ViewModel or global state flows in UI composables. `collectAsStateWithLifecycle()` automatically cancels flow collection when the app is in the background, conserving battery and system memory.
- **State Hoisting**: Keep reusable components stateless. Pass data models and event callbacks (`() -> Unit` or `(T) -> Unit`) rather than passing ViewModel instances down the widget tree.

### 2.2 Lazy Layouts Optimization
- **Mandatory `key`**: Every `items(...)` call in `LazyColumn` or `LazyRow` must provide a unique, stable key (e.g., `key = { it.id }`).
- **Mandatory `contentType`**: Every lazy list must provide a `contentType` lambda (e.g., `contentType = { "bookmark_card" }`) to enable item composition recycling across scroll boundaries.
- **Fixed items**: Single `item { ... }` blocks inside lazy lists should also specify explicit keys if there are multiple dynamic headers or spacers.

### 2.3 Recomposition & Computation
- **Remember & Derived State**:
  - Use `remember(var1, var2) { ... }` for computationally non-trivial operations (e.g., date formatting, localized label resolution, regex parsing).
  - Use `derivedStateOf` when calculating a state that changes less frequently than its source dependencies (e.g., scroll position thresholds).
- **Keyword Precomputation**: In `SearchScreen`, word tokens and tag tokens are parsed once at the screen level using `remember(searchQuery)` and passed down as immutable lists, completely eliminating per-item tokenization during scroll.

---

## 3. Coroutines, Threading, & Concurrency

### 3.1 Dispatchers
- **`Dispatchers.Main`**: Strictly reserved for UI rendering, event forwarding, and state mutations.
- **`Dispatchers.IO`**: Required for all disk I/O, `SharedPreferences` serialization/deserialization, Jsoup network fetching, and DOM parsing.
- **`viewModelScope`**: Use for ViewModel-initiated user actions.
- **`repositoryScope`**: Use dedicated `CoroutineScope(Dispatchers.IO + SupervisorJob())` inside singleton repositories for independent background persistence tasks that must outlive any individual screen lifecycle.

### 3.2 Mutual Exclusion & Synchronization
- Sequential pipelines (such as processing URLs through Gemini Nano in `QueueRepository`) must be protected with a coroutine `Mutex`:
  ```kotlin
  val executionMutex = Mutex()
  repositoryScope.launch {
      executionMutex.withLock {
          // Process one item at a time through AICore
      }
  }
  ```

---

## 4. Error Handling & Defensive Coding

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

## 5. Git Commit Workflow & Rules

### 5.1 Mandatory Commit for Every Instruction
- **Strict Rule**: Every AI agent and developer **MUST commit all changes upon completing each user instruction or task**.
- Never leave uncommitted changes at the end of an interaction turn.
- Before committing:
  1. Verify the build and tests pass (`./gradlew testDebugUnitTest`).
  2. Ensure documentation and relevant logs are up to date.
  3. Stage all modified and newly created files (`git add .`).

### 5.2 Conventional Commit Messages
- Use standard Conventional Commits format:
  - `feat(<scope>)`: New user-facing or architectural features.
  - `fix(<scope>)`: Bug fixes and behavioral repairs.
  - `perf(<scope>)`: Performance optimizations (recomposition, I/O dispatching, memory).
  - `refactor(<scope>)`: Code refactoring without behavior change.
  - `docs(<scope>)`: Creating or updating documentation and specifications.
  - `test(<scope>)`: Adding or updating unit/integration tests.
  - `chore(<scope>)`: Maintenance, Gradle scripts, configuration tweaks.
