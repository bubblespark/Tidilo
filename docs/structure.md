# Directory Structure & Module Responsibilities

This document defines the physical directory layout of the Tidilo codebase and outlines strict architectural responsibilities and component placement rules.

---

## 1. Directory Layout

```
app/src/main/java/com/ykgudev/tidilo/
├── MainActivity.kt                      # Main host Activity, Edge-to-Edge setup, bottom bar & scrim
├── ShareReceiverActivity.kt             # Transparent receiver for Android system share intents (ACTION_SEND)
├── TidiloApplication.kt                # Application singleton instance provider
│
├── config/                              # Global runtime configuration and user preferences
│   └── AppConfig.kt                     # Language (JA/EN), shared prefs
│
├── data/                                # Data layer & local storage
│   ├── AiCoreUsageRepository.kt         # Durable local inference usage aggregates and SDK attempt tracking
│   ├── CacheStorageRepository.kt        # Physical cache measurement and binary-unit formatting
│   ├── CollectionCounts.kt              # Atomic active/trash count projection
│   ├── BookmarkSource.kt                # Explicit ALL/MANUAL/RSS SQL query scope
│   ├── rss/                             # Feed acquisition, transactional ingestion, retention, preferences, scheduling
│   ├── notifications/                   # OS channels, Ready-threshold policy, RSS result publishing, destination intents
│   ├── BookmarkRepository.kt            # Bookmark CRUD, Trash lifecycle (30-day, 100-cap), JSON serialization
│   ├── DomainIconManager.kt             # Shared on-demand domain icon recovery, caching, color, and transparency
│   ├── DomainIconResolver.kt            # Prioritized HTML discovery and short-circuit icon selection
│   ├── EntityTagPolicy.kt               # Shared entity-tag normalization, deduplication, and bounds policy
│   ├── QueueRepository.kt               # Queue state machine (Running, Queuing, Error, Completed), Mutex
│   └── Taxonomy.kt                      # 7 Top-Level Categories & 11 Subcategories definition and i18n
│
├── parser/                              # Web extraction and DOM analysis engine
│   ├── WebPageData.kt                   # Data models: ParsedWebPage, WebMetaTag, JsonLdItem, ExtractedArticle
│   ├── RssFeedParser.kt                 # RSS2/Atom/RDF XML and normalized publisher metadata
│   └── WebPageParser.kt                 # Jsoup scraper, Readability4J integration, URL normalization
│
├── service/                             # Foundation model integration & AI pipelines
│   └── GeminiTaggingService.kt          # AICore client, prompt synthesis, strict JSON parsing, fallback logic
│
└── ui/                                  # Presentation layer (100% Jetpack Compose)
    ├── components/                      # Shared reusable UI elements
    │   ├── DomainWatermark.kt           # Shared 195dp, 50% saturation, 0.14 alpha domain icon watermark
    │   └── DomainIconState.kt           # Generation-scoped icon loading and completion observation
    │
    ├── icons/                           # Custom SVG / Vector icon assets and definitions
    │
    ├── navigation/                      # Navigation and root shell components
    │   ├── BarsState.kt                 # CompositionLocal for scroll-based bar visibility (LocalBarsVisible)
    │   ├── Screen.kt                    # Sealed route classes, titles, icons, deep-link templates
    │   ├── TidiloBottomBar.kt          # M3 Expressive Floating Navigation Bar (Library, Search, Queue)
    │   └── TidiloNavGraph.kt           # NavHost graph, screen declarations, spring transition animations
    │
    ├── screens/                         # Feature screens and screen-scoped ViewModels
    │   ├── debug/                       # Developer inspection tools (used by SettingsDebugScreen)
    │   │   ├── DebugPromptTester.kt     # Interactive prompt builder and test runner
    │   │   └── WebParserTestSection.kt  # URL input & DOM/OGP inspection workbench
    │   │
    │   ├── library/                     # Primary bookmark collection & search features
    │   │   ├── BookmarkCard.kt          # BookmarkItemCard, CompactTagBadge, keyword highlighting, skeletons
    │   │   ├── BookmarkDetailScreen.kt  # Bookmark inspection, browser launch, inline notes editing
    │   │   ├── BookmarkItem.kt          # BookmarkItem & Tag immutable data models
    │   │   ├── LibraryScreen.kt         # Infinite vertical feed of saved bookmarks, animated header, FAB filter
    │   │   ├── LibraryViewModel.kt      # Bookmark filtering, tokenized AND search logic, history tracking
    │   │   ├── SearchScreen.kt          # Main tab search view: Expressive header, 2-line history & live hit feed
    │   │   └── TagSelectionScreen.kt    # Mutual AND multi-tag selection dialog matrix
    │   │
    │   ├── queue/                       # Background scraping & AI tagging monitor
    │   │   ├── QueueScreen.kt           # 4-group collapsible monitor (Running, Queuing, Error, Completed)
    │   │   └── QueueViewModel.kt        # Enqueueing, promote-to-running, retry triggers
    │   │
    │   └── settings/                    # User preferences & LLM inference parameters
    │       ├── GenerateContentRequestScreen.kt    # Fine-grained tuning for Gemini Nano inference
    │       ├── GenerateContentRequestViewModel.kt # State management for inference parameters
    │       ├── SettingsDebugScreen.kt             # Diagnostics: AICore prompt API tests & Web Parser workbench
    │       ├── SummaryScreen.kt                   # Usage, collection, and cache statistics
    │       ├── SummaryViewModel.kt                # Independent section states and resumed lifecycle refresh
    │       ├── SummaryDate.kt                     # DST-aware local midnight scheduling
    │       ├── RssSettingsScreen.kt               # Subscription editor, daily time and Wi-Fi-only controls
    │       ├── RssSettingsViewModel.kt            # Feed/settings state and lifecycle network observation
    │       └── SettingsScreen.kt                  # App language selection, image cache clearing, Debug navigation
    │
    └── theme/                           # Design tokens & Material 3 theme styling
        ├── Color.kt                     # M3 light/dark palette, primary/secondary/container colors
        ├── Shape.kt                     # Expressive rounding (8dp, 12dp, 20dp, 28dp, 36dp)
        ├── Theme.kt                     # Dynamic color (Pixel/Material You) provider and fallback
        └── Type.kt                      # Expressive typography scale
```

---

## 2. Package Responsibilities

### `me.bubblespark.tidilo.config`
- Manages application-wide settings that affect multiple independent layers (e.g., UI language, whether developer debug tabs are exposed).
- Backed by Android `SharedPreferences`.

### `me.bubblespark.tidilo.data`
- Single source of truth for all business entities (`BookmarkItem`, `QueueItem`).
- Owns persistent storage operations, in-memory caching via `StateFlow`, concurrency synchronization (`Mutex`), and schema migrations.
- `AiProcessingWorker.kt` owns the persistent sequential import worker, retained wake-up signals, and session resource lifecycle; `ImportRepository` supplies Room task selection, inference, and error recovery.
- **Rule**: Repositories must never reference Compose APIs or Android Activities.

### `me.bubblespark.tidilo.parser`
- Handles HTTP/HTTPS web fetching, URL normalization, redirect following, and DOM extraction.
- Completely isolated from UI and AI inference logic. Pure I/O and text processing.

### `me.bubblespark.tidilo.service`
- Encapsulates interaction with the Google ML Kit GenAI Prompt API and AICore.
- Converts parsed web documents into prompt strings, issues requests to Gemini Nano, parses raw output into structured JSON, and performs graceful fallback.

### `me.bubblespark.tidilo.ui`
- Responsible for all user-facing pixels and input handling.
- Divided into:
  - `navigation/`: Root navigation graph, bar animation state, bottom navigation.
  - `screens/`: Feature modules. Each feature folder contains its Composable screens, screen-specific widgets, and associated `ViewModel`.
  - `theme/`: Styling tokens ensuring consistent Material 3 Expressive aesthetics.

---

## 3. Component Creation Rules

1. **Adding a New Screen**:
   - Create a subpackage under `ui/screens/<feature_name>/`.
   - Define a new entry in `ui/navigation/Screen.kt` with its route, title, and icons.
   - Register the route in `ui/navigation/TidiloNavGraph.kt`.
   - Never inject `NavController` directly deep into sub-composables; expose lambda callbacks (e.g., `onNavigateBack`, `onNavigateToDetail`).

2. **Adding a New Repository or Data Source**:
   - Place in `data/`.
   - Expose immutable `StateFlow<List<T>>` for reactive data streams.
   - Run serialization and disk writes in background coroutines (`Dispatchers.IO`).
   - Implement thread-safe singleton pattern using double-checked locking (`@Volatile INSTANCE`).

3. **Adding a New UI Component / Widget**:
   - If screen-specific, place in the corresponding `ui/screens/<feature>/` file.
   - If reusable across multiple screens (e.g., cards, chips, dialogs), place in a shared file or `ui/screens/library/BookmarkCard.kt`.
   - Annotate composables with `@Composable` and accept a standard `modifier: Modifier = Modifier` parameter.

## Shared article acquisition

`parser/ArticleAcquirer.kt` owns HTML acquisition through default or request-scoped transport; `parser/ArticlePreparation.kt` defines source-independent metadata preparation. RSS XML parsing remains in `RssFeedParser`; `RssIngestionStore` owns transactional staging/completion/receipts and feed-failure disabling. `RssRepository` coordinates bounded acquisition, durable retry, and optional image enrichment. Foreground AI remains owned by `ImportRepository`.

## RSS Library presentation

`data/db/RssFeedGroup.kt` defines the feed/count SQL projection, and `RssDao` observes visible saved groups. `LibraryViewModel` owns feed selection, ordering, and feed-scoped Paging/count queries. `ui/screens/library/RssFeedCard.kt` supplies feed content to the shared `LibraryDeckCard` in DomainCard.kt. `ui/components/FeedIconState.kt` observes on-demand feed/publisher artwork and dominant colors; DomainIconManager owns storage, processing, fallback, and cache generation. RSS and Domain drill-downs share LibraryScreen's bookmark layouts and ambient background.
