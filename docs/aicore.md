# AICore & Gemini Nano Architecture and Prompt Design Specification

This document details optional on-device AI integration (AI Analysis can be disabled), prompt engineering architecture, generation configuration parameters, and output parsing for Tidilo.

## 1. Overview & On-Device AI Architecture

### Manual analysis

Manual URL entry, sharing, and RSS acquisition save directly without blocking on AI ([direct import & metadata preparation](bookmark_classification.md)). From Bookmark Detail, the user can trigger on-demand AI review (`BookmarkAiReviewScreen`) independently from the saved URL; applying a proposal updates the bookmark, while closing review preserves the stored record. Model availability checks do not run inference.

### Key Technical Attributes
- **Library Coordinate**: `com.google.mlkit:genai-prompt:1.0.0-beta2`
- **Underlying Engine**: Google AICore System Service powering **Gemini Nano**.
- **Execution Target**: Dedicated hardware NPU (e.g., Google Tensor G3 / G4 on Pixel 8 / 9 / 10 / 11 devices).
- **Execution Thread**: `Dispatchers.IO` for all model preparation, IPC dispatch, and post-processing.
- **Resource Lifecycle**: Every `GenerativeModel` instance created via `Generation.getClient()` must be explicitly released via `model.close()` inside a `finally` block (including manual review) to free binder tokens and GPU/NPU memory.

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
Although ML Kit exposes `model.download()` returning `Flow<DownloadStatus>` when `status == FeatureStatus.DOWNLOADABLE`, Tidilo relies on the Android AICore system service for model delivery and guides users to system settings when not ready.

## 3. Web Content Preprocessing Pipeline

To fit within the limited context window of on-device LLMs and minimize inference latency and RAM usage, the raw HTML is cleaned and truncated before prompt assembly:

1. **Title & OGP Extraction**:
   - Priority 1: `og:title` from `<meta property="og:title">`.
   - Priority 2: `<title>` element.
   - Priority 3: Fallback to host domain (e.g. `github.com`).
2. **Metadata Description**:
   - `og:description` or `<meta name="description">`.
3. **Body Text Normalization**:
   - **Ad-Block DOM Filter**: After metadata and icon extraction, remove elements whose class or ID contains a case-insensitive, whitespace/hyphen/underscore-delimited `ad`, `ads`, `advert`, `advertisement`, `advertising`, or `adsbygoogle` token, plus `af_box` class/ID widgets, while preserving `html`, `body`, `main`, and `article` roots. Substring matches (e.g., `download`, `af_boxed`), product names, prices, and purchase links remain untouched.
   - **Extraction & Normalization**: Reuse Readability when no blocks were removed; otherwise re-extract from the filtered DOM, falling back only to filtered Jsoup text (never original ads). Clean newlines (`replace("\n", " ")`) and hard-truncate to the first **800 characters** (`bodySnippet.take(800)`).
   - **Prompt Preview Contract**: Both `ArticlePreparation` and the Debug Web Parser Prompt mode consume `ParsedWebPage.promptBodyText` (full ad-filtered body before 800-char truncation/newline normalization). Filtering updates neither staged snippets nor saved tags, and HTTP error bodies remain inspectable without preparing inference. See [Debug Web Parser UI](ui_settings.md#4-debug-web-parser).

## 4. Prompt Engineering Architecture

The prompt is constructed dynamically by `GeminiTaggingService.buildPrompt()`. It uses a strict system instruction structure comprising four rule sets, an explicit JSON schema, and the input payload.

### Complete Prompt Structure

```
You are a Web Content Tagging and Categorization Assistant.
Analyze the provided web page information, write a one- or two-sentence summary, accurately classify categories, extract entity tags, and respond with ONLY a pure JSON string.

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
  "summary": "one- or two-sentence summary",
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

## 5. Summary Rules Design (JA vs. EN)

The summary instruction adapts to `AppConfig.currentLanguage`, focusing strictly on what the page introduces, explains, or provides (no dictionary definitions, sales talk, or subjective opinions):

| Language | Length Guidance |
| :--- | :--- |
| **Japanese (`AppLanguage.JAPANESE`)** | 1–2 concise sentences (~50–100 characters). |
| **English (`AppLanguage.ENGLISH`)** | 1–2 concise sentences (< 200 characters, ~15–40 words). |

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
| **`maxOutputTokens`** | `256` | Yes (1–2048) | Default token budget for the summary and structured tags; configurable independently of summary length guidance. |
| **`temperature`** | *Default (0.0)* | Yes (0.0–2.0) | Defaults to greedy deterministic decoding (0.0) to strictly enforce JSON formatting and taxonomy adherence without hallucination. |
| **`topK`** | *Default (40)* | Yes (1–100) | Restricts candidate tokens to top probable cluster. |
| **`candidateCount`** | `1` | Yes (1–4) | Single candidate generation minimizes memory pressure and execution time on mobile NPUs. |
| **`seed`** | *Unset* | Yes (Long) | Optional seed for reproducibility during debugging and diagnostic testing. |
| **`cachedContextName`**| *Unset* | Yes (String) | Context caching identifier for reuse across consecutive requests if enabled by system. |

## 7. Output Parsing & Error Resilience Pipeline

On-device SLMs can occasionally emit conversational preambles or markdown fences. `GeminiTaggingService` applies four defensive stages:

1. **`extractJsonSubstring`**: Extracts between the first `'{'` and last `'}'`.
2. **`JSONObject` Parsing**: Extracts `"summary"`, `"category"`, `"subcategory"`, and `"entities"`.
3. **Taxonomy & Entity Guards**: `validateCategory()` (fallback: `Article`), `validateSubcategory()` (fallback: `Tech`), and `isCleanEntity()` (deduplicated; forbids years `"2024"`–`"2027"` and noise terms `"latest"`, `"最新"`, `"最強"`, `"まとめ"`, `"入門"`, `"おすすめ"`, `"guide"`).
4. **Full Metadata Fallback**: On any JSON or runtime error, falls back to `og:description`, `og:site_name`, and default taxonomy keys.

## 8. Error Handling & Rate Limiting

Manual review displays generation failures in the review UI. Acquisition and persistence failures remain in the direct import task list for retry.

## 9. Diagnostic & Test Tooling

- **`DebugPromptTester.kt`**: Low-level status checker (`checkStatus()`) and raw single-shot prompt tester with custom hyperparameter overrides.
- **Settings ➔ Debug ➔ ML Kit GenAI Prompt API Test**: Live UI in `SettingsDebugScreen.kt` to verify latency, status codes, and model output on physical Pixel devices.

### Release SDK initialization and shrinking

ML Kit discovers `CommonComponentRegistrar` from manifest metadata and instantiates it via its public no-argument constructor. Keep that constructor explicitly in `app/proguard-rules.pro` (keeping the class name alone is stripped by R8, causing `NoSuchMethodException` and a message-less `NullPointerException` in `Generation.getClient()`), while keeping R8 minification and resource shrinking enabled.

Verify the actual release manifest and DEX after shrinking:

```sh
./gradlew assembleRelease
python tools/verify_mlkit_release.py
```

`verify_mlkit_release.py` requires Android SDK Command-Line Tools (`apkanalyzer` via `ANDROID_HOME` or `ANDROID_SDK_ROOT`; override via `--apkanalyzer` / `--apk`). It confirms every declared ML Kit registrar keeps its public no-arg constructor, but does not validate device/model readiness; confirm `Check Status`, `Run Test`, and detail-screen AI Review on a supported physical Pixel device using the signed release APK.

## 10. Local Inference Usage Accounting

`GeminiTaggingService.runNanoInference` and `DebugPromptTester` SDK calls are wrapped by `AiCoreUsageRepository` at the SDK attempt boundary (including retries; setup, parsing, and persistence excluded). Results and cancellation retain original behavior if stats persistence fails. `AiCoreUsageRepository` tracks daily outcomes and the first quota-27 benchmark per start date. See [Application Summary](application_summary.md).
