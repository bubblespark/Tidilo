# Architecture Overview & Application Specification

## 1. Application Overview

**Tidilo** is a next-generation Android bookmarking and personal knowledge management application powered by on-device AI.  
Instead of manual folder sorting or manual tagging, Tidilo leverages Google's **Gemini Nano** foundation model running locally via the Android system **AICore** service. When a user shares a URL from a web browser or enqueues a link within the app, Tidilo scrapes the page, parses semantic metadata (OGP, meta tags, Mozilla Readability article body), resolves wrappers or shortened links, and invokes Gemini Nano on-device to automatically:
1. Synthesize an objective, one-sentence summary (in Japanese or English depending on user settings).
2. Classify the content into one of **7 Top-Level Categories** (Article, Media, Tool, Commerce, Community, Official, Reference).
3. Classify into one of **11 Subcategories** (Tech, Business, Society, Life, Food, Travel, Game, Entertainment, Hobby, Adult, Education).
4. Extract 2 to 10 canonical **Entity Keywords** (tools, brand names, technologies, creative works), strictly filtering noise like years, buzzwords, or generic modifiers.
5. Save the enriched bookmark into the user's permanent, searchable local library.

---

## 2. Core Architectural Patterns

Tidilo is built with modern **Android Clean Architecture** and **Unidirectional Data Flow (UDF)** principles using 100% Jetpack Compose and Kotlin Coroutines/Flow.

```mermaid
flowchart TD
    subgraph UI_Layer ["Presentation Layer (Jetpack Compose)"]
        Activity["MainActivity / ShareReceiverActivity"]
        NavGraph["TidiloNavGraph (Navigation Compose)"]
        Screens["Screens: LibraryScreen, SearchScreen, QueueScreen, etc."]
        ViewModels["ViewModels: LibraryViewModel, QueueViewModel, etc."]
    end

    subgraph Service_Layer ["Domain / Processing Pipeline"]
        Scraper["WebPageParser (Jsoup + Readability4J)"]
        AICoreService["GeminiTaggingService (ML Kit GenAI Prompt SDK)"]
        TaxonomyEngine["Taxonomy Validation & Normalization"]
    end

    subgraph Data_Layer ["Data & Persistence Layer"]
        BRepo["BookmarkRepository (StateFlow + SharedPreferences)"]
        QRepo["QueueRepository (StateFlow + Mutex + SharedPreferences)"]
        Config["AppConfig (Language, Debug Flags)"]
    end

    Activity --> NavGraph
    NavGraph --> Screens
    Screens <--> ViewModels
    ViewModels <--> BRepo
    ViewModels <--> QRepo
    QRepo --> Scraper
    QRepo --> AICoreService
    AICoreService --> Scraper
    AICoreService --> TaxonomyEngine
    AICoreService --> BRepo
```

### 2.1 Unidirectional Data Flow (UDF)
- **State flows down**: Repositories expose immutable `StateFlow<List<T>>` objects. ViewModels transform, filter, and expose these states via `stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ...)`. Composable screens observe states via `collectAsStateWithLifecycle()`.
- **Events flow up**: User interactions (taps, text input, chip selections) trigger ViewModel functions (`enqueueUrl`, `onSearchQueryChanged`, `updateBookmarkNote`), which delegate mutations to repositories or background services.

### 2.2 Offline-First & Local Storage (Room SQLite)
- Bookmark and trash records are persisted in a robust local SQLite database via AndroidX Room (`AppDatabase`, `BookmarkEntity`, `TrashedBookmarkEntity`).
- Indexed queries on `url`, `createdAt`, `category`, `subcategory`, and `isNsfw` guarantee sub-millisecond retrieval and data integrity with full ACID transaction safety.
- Reads and updates are memory-cached via in-memory `MutableStateFlow` buffers to ensure instant UI rendering with 0ms perceived lag, reactively synchronized via Room's reactive `Flow` stream.
- An automatic migration bridge on startup safely ingests legacy `SharedPreferences` JSON caches into Room on first launch with an empty database.

### 2.3 On-Device GenAI (AICore & Gemini Nano)
- Powered by `com.google.mlkit:genai-prompt:1.0.0-beta2`.
- Direct communication with the Android OS AICore system service without cloud round-trips, ensuring zero bandwidth consumption, ultra-low latency, and total user privacy.
- Strict token budgeting: requests configure `maxOutputTokens = 256` to ensure optimal throughput and instant inference.

---

## 3. Target Environment & Runtime Constraints

| Specification | Target / Requirement | Rationale |
| :--- | :--- | :--- |
| **Minimum SDK** | `minSdk 34` (Android 14) | Required for hardware-accelerated AICore and Gemini Nano system service availability. |
| **Compile & Target SDK** | `compileSdk 36`, `targetSdk 36` (Android 16 preview / Android 15+) | Full support for modern edge-to-edge system insets and Predictive Back gestures. |
| **JVM Target** | Java 21 | Modern language features, pattern matching, optimized runtime compilation. |
| **Target Hardware** | Google Pixel 8 / 9 / 10 / 11 series & AICore-enabled hardware | Hardware NPU capability for real-time on-device LLM inference. |
| **Form Factor** | Modern Android smartphones & foldable devices | Edge-to-edge layout, responsive container width (75% floating bar), dynamic system bars. |
