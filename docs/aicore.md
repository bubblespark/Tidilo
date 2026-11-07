# AICore & Gemini Nano Architecture and Prompt Design Specification

This document details the optional on-device AI integration (AI Analysis can be disabled; see below), prompt engineering architecture, generation configuration parameters, and output parsing pipeline for Tidilo.

---

## 1. Overview & On-Device AI Architecture

### Manual analysis

Manual URL entry, sharing, and RSS acquisition save directly. After saving, the Detail screen opens the existing AI review for the selected bookmark. Generation uses on-device Gemini Nano. Applying a proposal updates the bookmark, and closing review preserves the stored record. Model availability checks do not run inference.

### Key Technical Attributes
- **Library Coordinate**: `com.google.mlkit:genai-prompt:1.0.0-beta2`
- **Underlying Engine**: Google AICore System Service powering **Gemini Nano**.
- **Execution Target**: Dedicated hardware NPU (e.g., Google Tensor G3 / G4 on Pixel 8 / 9 / 10 / 11 devices).
- **Execution Thread**: `Dispatchers.IO` for all model preparation, IPC dispatch, and post-processing.
- **Resource Lifecycle**: Every `GenerativeModel` instance created via `Generation.getClient()` must be explicitly released using `model.close()` inside a `finally` block to release system binder tokens and GPU/NPU memory.

The manual review owns its generation lifecycle and closes the model when finished.

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
   - Keep the original Readability extraction for debug comparison. After metadata and icon extraction, remove elements whose class or ID contains a case-insensitive, whitespace/hyphen/underscore-delimited `ad`, `ads`, `advert`, `advertisement`, `advertising`, or `adsbygoogle` token, plus common `af_box` class/ID widgets. Preserve `html`, `body`, `main`, and `article` roots.
   - This common filter has no site-specific rules. Substring matches such as `download` and `af_boxed` remain untouched; product names, prices, and purchase links alone do not trigger removal. Reuse Readability when no blocks were removed; otherwise extract again from the filtered DOM, falling back only to filtered Jsoup text. An empty filtered result never falls back to the original ads.
   - Extracted using Readability4J (main article content cleaner) with fallback to Jsoup body text.
   - Cleaned of excessive whitespace and line breaks (`replace("\n", " ")`).
   - **Context Window Ceiling**: Hard truncated to the first **800 characters** (`bodySnippet.take(800)`).
The debug web parser's Body Text panel offers Readability4J, Jsoup Clean, and Prompt modes. Readability4J shows the original extraction; Prompt displays and copies only the full ad-filtered body before the 800-character limit, without instructions or metadata. Both the preview and ArticlePreparation consume ParsedWebPage.promptBodyText; production applies its existing truncation and newline replacement afterward. HTTP error bodies remain inspectable without preparing an inference request. The preview reflects the current parse, not a previously queued snippet; existing prepared tasks and saved tags are not rewritten by the filter.

---

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

---

## 5. Summary Rules Design (JA vs. EN)

The summary instruction is conditioned on `AppConfig.currentLanguage`:

### Japanese (`AppLanguage.JAPANESE`)
- **Length**: One or two concise sentences (**approximately 50-100 characters**).
- **Core Directive**: Focus on what this web page **introduces, explains, or provides**, NOT the dictionary definition of the subject.
- **Tone**: Strictly objective facts; no promotional sales talk or subjective opinions.

### English (`AppLanguage.ENGLISH`)
- **Length**: One or two concise sentences (**under 200 characters**, approx. 15–40 words).
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
| **`maxOutputTokens`** | `256` | Yes (1–2048) | Default token budget for the summary and structured tags; configurable independently of summary length guidance. |
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

Manual review displays generation failures in the review UI. Acquisition and persistence failures remain in the direct import queue for retry.

## 9. Diagnostic & Test Tooling

Developers and QA can test and inspect AICore behavior using:
- **`DebugPromptTester.kt`**: Low-level status checker (`checkStatus()`) and single-shot raw prompt tester with custom hyperparameter overrides.
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

For #113, the verifier failed on the original minified APK and passed after the targeted keep rule. A separate local instrumentation probe also changed from a client-construction `NullPointerException` to successful construction and closure without invoking AICore. Confirm `Check Status`, `Run Test`, and AI Review from a saved bookmark’s Detail screen on a supported physical device using the signed release build; emulator initialization checks do not validate inference.


## Local Inference Usage Accounting

The production `GeminiTaggingService.runNanoInference` and debug `DebugPromptTester` SDK calls are wrapped by `AiCoreUsageRepository`. Counting occurs at the SDK attempt boundary, including retries; setup, parsing, and persistence are excluded. Results and cancellation retain their original behavior even if statistics cannot be saved. AiCoreUsageRepository tracks daily outcomes and the first quota-27 detection benchmark per start date, without suggesting a fixed quota or midnight reset. See [Application Summary](application_summary.md) for the complete contract.

## Direct import preparation

Manual and RSS acquisition share article preparation and standard metadata classification. Inference input for manual review is prepared independently from the selected bookmark URL.
