# AICore & Gemini Nano Architecture and Prompt Design Specification

This document details the on-device AI integration, prompt engineering architecture, generation configuration parameters, and output parsing pipeline for Tidilo.

---

## 1. Overview & On-Device AI Architecture

Tidilo processes and categorizes web bookmarks locally on-device without transmitting user browsing data, URLs, or page contents to external cloud servers.

```
+-------------------------------------------------------------------------------+
|                             Android User Space                                |
|                                                                               |
|  [QueueRepository / Worker]                                                   |
|             │                                                                 |
|             ▼                                                                 |
|  [WebPageParser] ──> Jsoup & Readability4J (DOM parsing & content extraction)  |
|             │                                                                 |
|             ▼                                                                 |
|  [GeminiTaggingService] ──> Constructs Structured Prompt                       |
|             │                                                                 |
|             ▼                                                                 |
|  [ML Kit GenAI Prompt API] (com.google.mlkit:genai-prompt:1.0.0-beta2)         |
|             │                                                                 |
|             ▼ (IPC via Android System Service)                                |
|  [Google AICore Service] ──> Gemini Nano Foundation Model (Tensor NPU)        |
+-------------------------------------------------------------------------------+
```

### Key Technical Attributes
- **Library Coordinate**: `com.google.mlkit:genai-prompt:1.0.0-beta2`
- **Underlying Engine**: Google AICore System Service powering **Gemini Nano**.
- **Execution Target**: Dedicated hardware NPU (e.g., Google Tensor G3 / G4 on Pixel 8 / 9 / 10 / 11 devices).
- **Execution Thread**: `Dispatchers.IO` for all model preparation, IPC dispatch, and post-processing.
- **Resource Lifecycle**: Every `GenerativeModel` instance created via `Generation.getClient()` must be explicitly released using `model.close()` inside a `finally` block to release system binder tokens and GPU/NPU memory.

### Import Processing Sessions

- `ImportRepository` uses one persistent `AiProcessingWorker` coroutine. Bulk analysis, individual promotion, error retry, and foreground resume all signal this worker after persisting runnable task states.
- A conflated channel retains wake-up requests, including requests arriving between the final empty Room query and session shutdown. Repeated requests never start concurrent inference workers.
- Each processing session holds `executionMutex` once, lazily opens one `GenerativeModel`, and reuses it until no runnable tasks remain or background execution is paused. The model is closed in `finally`, including coroutine cancellation and failure paths. Idle workers hold no model.
- `GeminiTaggingService.processAndSaveBookmark` and `runNanoInference` use the caller-owned model. Standalone `generateProposal` calls own and close their individual models.
- Worker cancellation is distinguished from dependency-originated `CancellationException` using the current coroutine's active state. Actual worker cancellation propagates and closes the model; task-level interruptions use existing failure recovery, and session-level interruptions are reported without terminating the signal consumer.
- Session failures, including task-selection failures, close the current model and automatically retry up to three times at one-second intervals without requiring a new wake-up signal. Exhausting the retry budget leaves persisted tasks intact and the worker available for a later external wake-up.
- Tasks are selected from Room in its existing newest-first order. BUSY and foreground BACKGROUND_USE_BLOCKED retries retain their three-second backoff and maximum of three retries. Other eligible tasks can run during backoff; the session remains open while waiting, and new work wakes the wait early.
- Cancellation restores waiting tasks to Ready while allowing the active inference to finish. Background suspension preserves waiting tasks for foreground resume. Quota handling, fallback saving, and batch error transitions complete before the next task is selected.
- Both normal AI results and fallback bookmarks use `BookmarkRepository.addBookmarkAndAwait`, which awaits a transactional bookmark replacement before import-task deletion. Failed replacements retain existing bookmark records. A fallback save failure retains the import task in Error for manual retry; auxiliary metadata and cache work run separately after successful persistence.
- Unit tests inject model creation, inference, foreground state, fallback mode, and coroutine scope so session ownership and recovery can be verified without AICore or an emulator inference attempt.
- Retry interval injection defaults to 3000 milliseconds in production and permits a short interval in retry regression tests.

---

## 2. Device Readiness & Status Checking

Before dispatching prompts, the system verifies model availability through the AICore client:

```kotlin
val model = Generation.getClient()
try {
    val status = model.checkStatus()
    when (status) {
        FeatureStatus.AVAILABLE    -> // Ready for zero-latency inference
        FeatureStatus.DOWNLOADABLE -> // Model supported but requires OTA download
        FeatureStatus.DOWNLOADING  -> // Model download actively in progress
        FeatureStatus.UNAVAILABLE  -> // Device, OS, or SoC lacks Gemini Nano support
    }
} finally {
    model.close()
}
```

### Background Model Download
When `status == FeatureStatus.DOWNLOADABLE`, the app can initiate a background download stream via `model.download()`, returning a `Flow<DownloadStatus>` that emits byte progress and completion states.

---

## 3. Web Content Preprocessing Pipeline

To fit within the limited context window of on-device LLMs and minimize inference latency and RAM usage, the raw HTML is cleaned and truncated before prompt assembly:

1. **Title & OGP Extraction**:
   - Priority 1: `og:title` from `<meta property="og:title">`.
   - Priority 2: `<title>` element.
   - Priority 3: Fallback to host domain (e.g. `github.com`).
2. **Metadata Description**:
   - `og:description` or `<meta name="description">`.
3. **Body Text Normalization**:
   - Extracted using Readability4J (main article content cleaner) with fallback to Jsoup body text.
   - Cleaned of excessive whitespace and line breaks (`replace("\n", " ")`).
   - **Context Window Ceiling**: Hard truncated to the first **800 characters** (`bodySnippet.take(800)`).
4. **Enqueue-Time Pre-Parsing & Fast Path Architecture**:
   - HTML fetching, Readability4J body extraction, OGP metadata detection, and domain icon caching are executed and completed **immediately at URL ingestion time (enqueue)**.
   - The precomputed snippets (`bodySnippet`, `description`, `siteName`, `isNsfw`) are stored in `QueueItem` and persisted into preferences.
   - When a task transitions to **Running**, `GeminiTaggingService` activates the **Fast Path**, completely bypassing redundant HTTP network calls and DOM parsing to invoke on-device AICore inference with zero I/O delay, slashing Running task latency from ~1.8s to ~1.0s.

---

## 4. Prompt Engineering Architecture

The prompt is constructed dynamically by `GeminiTaggingService.buildPrompt()`. It uses a strict system instruction structure comprising four rule sets, an explicit JSON schema, and the input payload.

### Complete Prompt Structure

```
You are a Web Content Tagging and Categorization Assistant.
Analyze the provided web page information, write a 1-sentence summary, accurately classify categories, extract entity tags, and respond with ONLY a pure JSON string.

[1. Summary Rules]
<Language-specific summary instruction>

[2. Category Guidelines]
Select EXACTLY 1 most suitable category from the following 7 choices (use exact English key):
- Article: Articles, blog posts, editorials, news, technical explainers, opinions.
- Media: Illustrations, artwork, photos, videos, music (e.g., Pixiv, YouTube, portfolios).
- Tool: Web applications, utilities, SaaS, code repositories, productivity tools (e.g., GitHub, Figma, online tools).
- Commerce: E-commerce, online stores, product sales, pricing pages (e.g., Amazon, shopping sites).
- Community: Social networks, discussion forums, Q&A, online communities (e.g., X/Twitter, Reddit, Discord).
- Official: Company official portals, product landing pages, corporate overviews.
- Reference: Official documentation, API references, dictionaries, wikis, specifications (e.g., MDN, Android Docs, Wikipedia).

[3. Subcategory Rules]
Select EXACTLY 1 most relevant subcategory from the following 11 choices (use exact English key):
- Tech: Software, hardware, AI, programming, IT gadgets, engineering, internet infrastructure.
- Business: Startups, finance, marketing, corporate management, careers, economy, B2B services.
- Society: News, politics, law, environmental issues, social phenomena, culture, public affairs.
- Life: Daily living, housing, health, wellness, relationships, parenting, personal finance, self-care.
- Food: Recipes, cooking, restaurants, gourmet reviews, beverages, food culture, dining guides.
- Travel: Tourism, local guides, hotels, transport, outdoor adventures, destination tips, sightseeing.
- Game: Video games, board games, esports, gameplay guides, gaming news, game development.
- Entertainment: Movies, TV shows, anime, manga, pop music, celebrity news, theater, humor.
- Hobby: Crafts, photography, DIY, fitness, collecting, pet care, cars, personal creative pursuits.
- Adult: NSFW content, pornography, mature dating services, erotic illustrations, age-restricted themes (18+).
- Education: Academic subjects, language learning, tutorials, study resources, qualifications, courses.

[4. Entities Tag Rules]
- Extract between 2 to 10 specific noun keywords (e.g., tool/product names, company/brand names, technologies, creative work titles).
- STRICTLY PROHIBIT the following noise expressions:
  * Dates, years, and temporal terms (e.g., "2026", "2025", "latest", "October", "spring", "today").
  * Subjective or exaggerated words (e.g., "best", "greatest", "ultimate", "recommended", "useful").
  * Ambiguous modifiers, annotations, or generic formats (e.g., "guide (latest)", "summary", "for beginners", "how-to", "tutorial").
  * Generic abstract nouns (e.g., "settings", "features", "methods", "list").
- Keep the original or canonical notation as used in the content (e.g., keep "Android" as "Android", do NOT transliterate into Katakana "アンドロイド", and keep Japanese terms like "イラスト" in Japanese). Do NOT force translation.

[Output Format]
Respond with ONLY a valid JSON object. Do not include any explanations, markdown code blocks (such as ```json), or backticks.
{
  "summary": "1-sentence summary",
  "category": "One of the 7 Category keys",
  "subcategory": "One of the 11 Subcategory keys",
  "entities": ["entity1", "entity2", "entity3"]
}

[Input Web Page Info]
URL: <url>
Site: <siteName>
Title: <title>
Description: <description>
Content: <bodySnippet (up to 800 chars)>
```

---

## 5. Summary Rules Design (JA vs. EN)

The summary instruction is conditioned on `AppConfig.currentLanguage`:

### Japanese (`AppLanguage.JAPANESE`)
- **Length**: Exactly 1 concise sentence (**approximately 50 characters**).
- **Core Directive**: Focus on what this web page **introduces, explains, or provides**, NOT the dictionary definition of the subject.
- **Tone**: Strictly objective facts; no promotional sales talk or subjective opinions.

### English (`AppLanguage.ENGLISH`)
- **Length**: Exactly 1 concise sentence (**under 100 characters**, approx. 15–20 words).
- **Core Directive**: Focus on what this web page **introduces, explains, or provides**, NOT the dictionary definition of the subject.
- **Tone**: Strictly objective facts; no marketing hype.

---

## 6. Model Parameters & Hyperparameter Configuration

Inference is dispatched via `generateContentRequest`:

```kotlin
val request = generateContentRequest(TextPart(prompt)) {
    maxOutputTokens = 256
}
val response = model.generateContent(request)
```

### Parameter Reference & Production Settings

| Parameter | Production Value | Debug Configurable | Purpose & Rationale |
| :--- | :--- | :--- | :--- |
| **`maxOutputTokens`** | `256` | Yes (1–2048) | Capped at 256 tokens. A 1-sentence summary and 3 JSON keys never exceed ~150 tokens. Capping prevents runaway loops and reduces inference latency to < 1.2s. |
| **`temperature`** | *Default (0.0)* | Yes (0.0–2.0) | Defaults to greedy deterministic decoding (0.0) to strictly enforce JSON formatting and taxonomy adherence without hallucination. |
| **`topK`** | *Default (40)* | Yes (1–100) | Restricts candidate tokens to top probable cluster. |
| **`candidateCount`** | `1` | Yes (1–4) | Single candidate generation minimizes memory pressure and execution time on mobile NPUs. |
| **`seed`** | *Unset* | Yes (Long) | Optional seed for reproducibility during debugging and diagnostic testing. |
| **`cachedContextName`**| *Unset* | Yes (String) | Context caching identifier for reuse across consecutive requests if enabled by system. |

---

## 7. Output Parsing & Error Resilience Pipeline

On-device SLMs (Small Language Models) can occasionally produce conversational preambles (e.g., `Here is the JSON:`) or wrap output in markdown code fences. `GeminiTaggingService` employs multi-layered sanitization:

```
[Raw AI Output String]
          │
          ▼
[extractJsonSubstring] ──> Extracts between first '{' and last '}'
          │
          ▼
[JSONObject Parsing]   ──> Extracts "summary", "category", "subcategory", "entities"
          │
          ├─► validateCategory(): Strict match against 7 Category enum IDs (fallback: Article)
          │
          ├─► validateSubcategory(): Strict match against 11 Subcategory enum IDs (fallback: Tech)
          │
          └─► isCleanEntity(): Filters prohibited temporal terms, noise, and deduplicates (min 2, max 5)
          │
          ▼ (On any JSON or runtime error)
[Full Metadata Fallback] ──> og:description, og:site_name, default taxonomy keys
```

### Prohibited Entity Guard
`isCleanEntity()` verifies keywords against noise tokens:
- **Forbidden Years**: `"2024"`, `"2025"`, `"2026"`, `"2027"`.
- **Forbidden Noise Terms**: `"latest"`, `"最新"`, `"最強"`, `"まとめ"`, `"入門"`, `"おすすめ"`, `"guide"`.

---

## 8. Error Handling & Rate Limiting

The AICore system service enforces device-level thermal, power, and rate limits. The execution pipeline handles AICore error responses with the following deterministic recovery policies:

### Foreground-Only Execution Policy
AICore inference is only permitted while the app is in the foreground (`TidiloApplication.isAppInForeground == true`).
- If an item starts or encounters backgrounding during execution, its status is updated to `WAITING_AI` (`"Paused • Retrying when app is active"`).
- It remains in the `Running` queue and automatically resumes once the app returns to the foreground (`onAppBecameActive()`).

### Error Code Recovery Matrix

| Error Code / Name | GenAiException ErrorCode | Trigger Condition | System Behavior & Recovery Strategy |
| :--- | :--- | :--- | :--- |
| **`BUSY`** | `9` | AICore system service or NPU is temporarily overloaded. | Retry up to 3 times at 3-second intervals while in foreground (`WAITING_AI: "AICore busy • Retrying (x/3)..."`). If all 3 retries fail, abort the entire Running batch and move all Running items to `Error`. |
| **`PER_APP_BATTERY_USE_QUOTA_EXCEEDED`** | `27` | Daily on-device battery/power budget for AICore is exceeded. | **Without Fallback (`DISABLE`)**: Display an `AlertDialog` stating *"AICore使用制限を超過しました。URLを取り込めません。しばらく待ってから再度実行するか、フォールバック機能の仕様を検討してください。"* (EN: *"You have exceeded the AICore usage limit. We cannot import the URL. Please wait a while and try again, or enable the fallback feature."*). Move active task and other pending Running tasks back to `Queuing`.<br>**With Fallback (`QUOTA_EXCEEDED_ONLY` or `ALL`)**: Ingest item immediately as an **AI Skipped Bookmark** (`isAiSkipped = true`, `category = null`, `subcategory = null`, `entities = []`, `summary = description`). The error dialog is suppressed. |
| **`BACKGROUND_USE_BLOCKED`** | `30` | Execution blocked because app was detected in background by system service. | If app is in foreground, retry up to 3 times at 3-second intervals (`WAITING_AI: "AICore background blocked • Retrying (x/3)..."`). If retry limit reached: if fallback mode is `ALL`, ingest as AI Skipped Bookmark; otherwise fail entire Running batch to `Error`. If app is backgrounded, pause in `WAITING_AI` until foreground. |
| **Other Inference Errors / Retry Limit Exhausted** | `UNKNOWN`, `UNAVAILABLE`, etc. | Unrecoverable model faults, timeouts, or exhausted retries. | If fallback mode is `ALL`, convert item directly into an AI Skipped Bookmark (`isAiSkipped = true`). Otherwise, move the active task and pending Running tasks to the `Error` group (`"Batch stopped: <Error>"`). |
| **HTTP Non-200 / Network** | Pre-parsing network failure | Status != 200 during fetch | Skipped during ingestion/fast-path pre-parsing, marked as `HTTP Error <Code>`. Non-AICore errors do not fallback. |
| **JSON Parse Malformed** | SLM formatting anomaly | Unparseable JSON syntax | Gracefully caught in `GeminiTaggingService`; falls back to HTML OGP metadata without throwing an unrecoverable exception. |

### AICore Error Fallback Modes (`AppConfig.aiCoreFallbackMode`)
Users can configure the fallback behavior under **Settings ➔ AI ➔ AICoreエラー発生時のフォールバック**:
- **`無効 (Disable)`** *(Default)*: Standard behavior where quota exceeded triggers a warning dialog and other fatal errors push items to the `Error` list.
- **`AICore超過時のみ (Only if the AICore quota is exceeded)`**: Triggers fallback exclusively when error code `PER_APP_BATTERY_USE_QUOTA_EXCEEDED (27)` is returned. Items are saved into the Library as bookmarks with `isAiSkipped = true` without showing the error dialog.
- **`全て (All)`**: Applies fallback to quota exceeded and all AICore error codes (after retry exhaustion where applicable).

#### Fallback Bookmark Contract
- **`isAiSkipped`**: `true` (renders an "AI Skipped" badge in bookmark list and detail cards).
- **`tag`**: `Tag.createEmpty()` (`category = null`, `subcategory = null`, `entities = emptyList()`).
- **`description`**: Set to the HTML-parsed page description (`og:description` or `<meta name="description">`).
- **Domain Icons & Policies**: Cached and associated normally.

---

## 9. Diagnostic & Test Tooling

Developers and QA can test and inspect AICore behavior using:
- **`DebugPromptTester.kt`**: Low-level status checker (`checkStatus()`), model downloader (`startDownload()`), and single-shot raw prompt tester with custom hyperparameter overrides.
- **Settings ➔ Debug ➔ ML Kit GenAI Prompt API Test**: Live UI in `SettingsDebugScreen.kt` to test latency, status codes, and model responses on physical Pixel devices.

### Release SDK initialization and shrinking

ML Kit discovers `CommonComponentRegistrar` from manifest metadata and instantiates it through its public no-argument constructor. Keep that constructor in `app/proguard-rules.pro`; keeping the class name alone is insufficient with the current release toolchain. Keep R8 minification and resource shrinking enabled.

Issue #113 was reproduced before inference: the minified APK removed this constructor, component discovery logged `NoSuchMethodException`, and `SharedPrefManager` was never registered. `Generation.getClient()` then threw a message-less `NullPointerException` inside SDK logger initialization. This affected both imports and the diagnostic status/test paths. The generic UI error text alone does not identify the failing stage.

After building the release APK, check its actual manifest and DEX rather than only checking source rules:

```sh
./gradlew assembleRelease
python tools/verify_mlkit_release.py
```

The verifier requires Android SDK Command-Line Tools (`apkanalyzer`) and uses `ANDROID_HOME` or `ANDROID_SDK_ROOT`. Override paths with `--apkanalyzer` and `--apk` if necessary. It checks that every registrar declared by the ML Kit discovery service retains its public no-argument constructor. It does not run inference or establish device/model readiness.

For #113, the verifier failed on the original minified APK and passed after the targeted keep rule. A separate local instrumentation probe also changed from a client-construction `NullPointerException` to successful construction and closure without invoking AICore. Confirm `Check Status`, `Run Test`, and a normal import on a supported physical device using the signed release build; emulator initialization checks do not validate inference.


## Local Inference Usage Accounting

The production `GeminiTaggingService.runNanoInference` and debug `DebugPromptTester` SDK calls are wrapped by `AiCoreUsageRepository`. Counting occurs at the SDK attempt boundary, including retries; setup, parsing, and persistence are excluded. Results and cancellation retain their original behavior even if statistics cannot be saved. Settings Summary exposes daily outcomes and the first quota-27 detection benchmark per start date, without suggesting a fixed quota or midnight reset. See [Application Summary](application_summary.md) for the complete contract.

## RSS Foreground Inference

RSS workers never invoke AICore. RSS tasks use the common extracted article HTML input; legacy XML snippets require preparation before inference. The foreground worker atomically claims prepared valid tasks, prioritizes manual waiters, and preserves RSS first-seen/publication/source fields through AI success and fallback. The five-task cap applies only to unstarted tasks per feed; it is unrelated to retention or an SDK quota. See [RSS and Atom Subscriptions](rss.md).

## Shared manual/RSS prepared input

`ArticlePreparation` maps shared HTML extraction into title/site/Description/body input for manual and RSS imports. Both sources use the same prompt builder for identical HTML/settings; explicit manual title overrides remain intentional input. Legacy RSS XML tasks require HTML preparation before inference, even when their XML excerpt is nonblank. Insufficient HTML content and fetch failure remain acquisition errors. Scheduled work never invokes AICore, and Direct acquisition/retry bypasses AI entirely.

Missing extracted text is a per-article error, including for manual imports. It does not invoke AICore or stop other waiting AI tasks. Explicit retry of a manual content error invalidates its preparation marker so article HTML can be fetched again rather than permanently reusing empty input.

HTTP, connectivity, and other article acquisition failures during that re-fetch also remain per-article errors. They are distinguished from AICore failures and do not move unrelated waiting tasks to Error. Cancellation continues to propagate normally.
