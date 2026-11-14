# Directory Structure & Module Responsibilities

This document defines the physical directory layout of the Tidilo codebase, package responsibilities, and component placement rules.

## 1. Directory Layout

```
app/src/main/java/me/bubblespark/tidilo/
├── MainActivity.kt                      # Main host Activity, Edge-to-Edge setup, TidiloBackground & scrim
├── ShareReceiverActivity.kt             # Transparent receiver for Android system share intents (ACTION_SEND)
├── TidiloApplication.kt                 # Application singleton instance provider
│
├── config/                              # Global preferences, theme & AI mode settings
│   ├── AiAnalysisMode.kt / AiAnalysisPreference.kt
│   └── AppConfig.kt / AppTheme.kt
│
├── data/                                # Data layer, repositories & local persistence
│   ├── AiCoreAvailability.kt / AiCoreUsageRepository.kt
│   ├── BookmarkRepository.kt            # Bookmark CRUD, query recovery, detail persistence, Trash lifecycle
│   ├── BookmarkSource.kt / CollectionCounts.kt / CollectionPolicy.kt / CollectionRepository.kt
│   ├── CacheStorageRepository.kt        # Physical cache measurement and binary-unit formatting
│   ├── DomainIconManager.kt / DomainIconResolver.kt / OgImageManager.kt / OgImageFetchCoordinator.kt
│   ├── DomainPolicyManager.kt / DomainPolicyStore.kt
│   ├── ImportOutcome.kt / ImportRepository.kt / ImportTask.kt
│   ├── EntityCandidateExtractor.kt / EntitySkeletonKey.kt / EntityTagPolicy.kt
│   ├── Taxonomy.kt / TextSegmenter.kt
│   ├── cache/                           # MediaCacheStore, OgImageStore, DomainIconStore, MediaColorPalette
│   ├── db/                              # AppDatabase, DAOs (Bookmark/Collection/DomainPolicy/Import*/Rss*/Trash), Entities, BookmarkQueries, Migrations
│   ├── debug/                           # CrashLogManager
│   ├── maintenance/                     # RetentionPolicy, LibraryMaintenanceRepository, Scheduler, Worker
│   ├── notifications/                   # NotificationCoordinator, NotificationErrorDetail, RssNotificationRunPolicy
│   └── rss/                             # RssRepository, RssIngestionStore, RssFeedFetcher, RssFetchWorker, RssHttpClient, RssPolicy, RssPreferences, RssScheduler, RssTriggerTimePolicy
│
├── parser/                              # HTML/RSS extraction & standard metadata classification
│   ├── ArticleAcquirer.kt               # Default or request-scoped HTML acquisition
│   ├── ArticlePreparation.kt            # Source-independent metadata & prompt snippet preparation
│   ├── RssFeedParser.kt                 # RSS2/Atom/RDF XML parsing
│   ├── StandardBookmarkClassifier.kt    # Schema.org JSON-LD / Open Graph taxonomy classification
│   └── WebPageData.kt / WebPageParser.kt
│
├── service/                             # Foundation model integration & AI pipelines
│   └── GeminiTaggingService.kt          # AICore client, prompt synthesis, JSON parsing, metadata fallback
│
└── ui/                                  # Presentation layer (100% Jetpack Compose)
    ├── components/                      # Shared scaffolds, surfaces, headers, chips, watermarks, blur & image state
    │   ├── TidiloScreenScaffold.kt / TidiloFrostedHeader.kt / FrostedHeaderContainer.kt / HeaderDefaults.kt
    │   ├── TidiloSurface.kt / TidiloBackground.kt / ScreenBackdrop.kt / AmbientLighting.kt
    │   ├── TidiloChip.kt / TidiloIconButton.kt / TidiloAlertDialog.kt / TidiloDropdownMenu.kt / TidiloModalBottomSheet.kt
    │   ├── ChromeBlurBackground.kt / OverlayBlurBackground.kt / OverlayBlurHost.kt / OverlayLayerRegistry.kt / NativeOverlayModifier.kt / ScreenInsets.kt
    │   └── CacheVisualSource.kt / DomainWatermark.kt / DomainIconState.kt / FeedIconState.kt / OgImageState.kt
    │
    ├── navigation/                      # Root navigation graph & Library rail shell
    │   ├── BarsState.kt / LibraryNavigationRail.kt / LibrarySearchAction.kt / Screen.kt / TidiloNavGraph.kt
    │
    ├── screens/                         # Feature screens and ViewModels
    │   ├── debug/                       # DebugPromptTester.kt, WebParserTestSection.kt
    │   ├── importscreen/                # ImportUrlDialog.kt, ImportViewModel.kt
    │   ├── library/                     # LibraryScreen, LibraryViewModel, SearchScreen, BookmarkDetailScreen, BookmarkAiReviewScreen, BookmarkCard, BookmarkGridCard, BookmarkItem, BookmarkTagEditDialog, EntityTagPickerBottomSheet, CollectionCard, CollectionEditorBottomSheet, CollectionMembershipBottomSheet, DomainCard, RssFeedCard, TagSelectionScreen, PagingFeedback
    │   ├── notifications/               # NotificationListItem.kt, NotificationScreen.kt
    │   ├── settings/                    # SettingsScreen, AiAnalysisScreen, PrivateSettingsScreen, RssSettingsScreen, StorageScreen, GenerateContentRequestScreen, SettingsDebugScreen, SettingsComponents, ThemeBlurSetting
    │   └── trash/                       # TrashScreen.kt, TrashUiState.kt, TrashViewModel.kt
    │
    ├── theme/                           # M3 Expressive tokens, surface roles, shapes & blur policy
    │   ├── AppThemeStyle.kt / CollectionColors.kt / Color.kt / ModernColors.kt / OverlayBlurPolicy.kt
    │   └── Shape.kt / Theme.kt / ThemeModifiers.kt / ThemeShapeScale.kt / ThemeSurfaces.kt / ThemeTextDepth.kt / Type.kt
    │
    └── util/                            # TimeFormatter.kt
```

## 2. Package Responsibilities

### `me.bubblespark.tidilo.config`
- Manages application-wide settings (`AppConfig`, `AppTheme`, `AiAnalysisMode`, `AiAnalysisPreference`) backed by `SharedPreferences`.

### `me.bubblespark.tidilo.data`
- Owns persistent domain state (`BookmarkItem`, `ImportTask`, collections, feeds, trash), reactive flows, synchronization, and Room schema migrations.
- `BookmarkRepository` owns active library queries, detail persistence, and Trash lifecycle; live row projections and DAO queries live in `data/db/Trash*`. See [Trash](trash.md).
- `ImportRepository` owns direct URL import, duplicate resolution, and Room-backed task persistence/error recovery.
- `CollectionPolicy` and `CollectionRepository` (with `data/db/Collection*` & `BookmarkCollectionCrossRef`) own manual collection validation and transactions. See [Manual Collections](collections.md).
- `rss/` handles feed fetching (`RssFeedFetcher`, `RssFetchWorker`), transactional staging/completion receipts (`RssIngestionStore`), and bounded acquisition/retention (`RssRepository`). See [RSS](rss.md).
- `maintenance/` (`RetentionPolicy`, `LibraryMaintenanceRepository`, `LibraryMaintenanceScheduler`, `LibraryMaintenanceWorker`) schedules and executes network-independent daily RSS expiration and orphan file cleanup via `MediaCacheStore` / `MediaCacheLayout`. See [Daily Library Maintenance](library_maintenance.md).
- `cache/` (`MediaCacheStore`, `OgImageStore`, `DomainIconStore`, `MediaColorPalette`) owns unified file-rooted media caching, color extraction, and domain/feed artwork surfaced through `OgImageManager` / `DomainIconManager`.
- `TextSegmenter.kt` and `EntityCandidateExtractor.kt` extract candidate skeleton keys and 1-token word suggestions.
- **Rule**: Repositories must never reference Compose APIs or Android Activities.

### `me.bubblespark.tidilo.parser`
- Pure I/O and text processing isolated from UI and AI inference: `ArticleAcquirer` (HTML acquisition), `ArticlePreparation` (source-independent metadata prep), `StandardBookmarkClassifier` (Schema.org/OG classification), `WebPageParser`, and `RssFeedParser`.

### `me.bubblespark.tidilo.service`
- Encapsulates ML Kit GenAI Prompt API / AICore calls in `GeminiTaggingService` (prompt construction, JSON extraction/validation, metadata fallback).

### `me.bubblespark.tidilo.ui`
- Responsible for all user-facing UI and interaction:
  - `components/` & `navigation/`: Shared scaffolds (`TidiloScreenScaffold`), headers (`TidiloFrostedHeader`), surfaces (`TidiloSurface`, `TidiloChip`, `TidiloIconButton`), backdrop blur layers, feed/domain artwork observers (`FeedIconState`, `DomainIconState`), `TidiloNavGraph`, modal navigation rail, and Search action.
  - `screens/`: Feature modules (`library/` owns bookmark/domain/collection/RSS views, `RssFeedGroup` + `RssDao` feed presentation via `LibraryViewModel` and `RssFeedCard` ➔ `LibraryDeckCard` in `DomainCard.kt`, detail/AI review, and collection sheets; `trash/` owns the Trash list with no Trash Detail route; `importscreen/`, `notifications/`, `settings/`, `debug/`).
  - `theme/`: `ThemeSurfaces`, `ThemeShapeScale`, `AppThemeStyle`, and `Theme` coordinate Material and extended surface tokens (`BookmarkCardStyle.kt` holds only scoped accent input). Component catalogue checks live in Android test sources; see [UI design](ui_design.md#73-resolved-theme-and-shared-surface-contract) and [Developing app themes](theme_development.md).

## 3. Component Creation Rules

1. **Adding a New Screen**:
   - Create a subpackage under `ui/screens/<feature_name>/`, define its route in `ui/navigation/Screen.kt`, and register it in `ui/navigation/TidiloNavGraph.kt`.
   - Expose lambda callbacks (e.g., `onNavigateBack`) instead of passing `NavController` into child composables.
2. **Adding a New Repository or Data Source**:
   - Place in `data/`, expose immutable `StateFlow` / `Flow` streams, run I/O on `Dispatchers.IO`, and use double-checked locking (`@Volatile INSTANCE`) for singletons.
3. **Adding a New UI Component / Widget**:
   - Place screen-specific widgets under `ui/screens/<feature>/` and cross-screen reusable components under `ui/components/`.
   - Annotate with `@Composable` and accept `modifier: Modifier = Modifier`.
