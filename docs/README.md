# Tidilo Developer Documentation

Technical documentation for **Tidilo**, a local-first bookmark and RSS manager for Android with optional on-device AI. For the product overview, see the [project README](../README.md).

---

## Getting Started

**Requirements**: Android Studio (latest stable), JDK 21, a device or emulator running Android 14 (API 34) or higher.

```bash
git clone https://github.com/bubblespark/Tidilo.git
cd Tidilo

./gradlew testDebugUnitTest   # Run unit tests
./gradlew assembleDebug       # Build the debug APK
./gradlew installDebug        # Install on a connected device
```

> [!NOTE]
> AICore (Gemini Nano) is not available on emulators. AI Analysis can only be tested on a supported physical device. All other features work without it.

---

## Documentation Index

### Architecture & Structure

| Document | Contents |
| :--- | :--- |
| [overview.md](overview.md) | Application purpose, layered architecture, UDF data flow, system boundaries. |
| [structure.md](structure.md) | Directory layout, package responsibilities, rules for adding components. |
| [tech_stack.md](tech_stack.md) | Languages, SDKs, libraries and exact versions. |
| [data_models.md](data_models.md) | `BookmarkItem`, tags, taxonomy, import task models, Room schema, and migration invariants. |

### Features

| Document | Contents |
| :--- | :--- |
| [rss.md](rss.md) | RSS/Atom subscriptions, scheduled fetching, Wi-Fi policy, direct ingestion, deduplication, retention. |
| [bookmark_classification.md](bookmark_classification.md) | Standard HTML evidence, supported category mappings, and save behavior without AI. |
| [collections.md](collections.md) | Manual bookmark collections: persistence, Library and editor UI, bookmark assignment. |
| [trash.md](trash.md) | Trash list view, 30-day / 100-item retention, restore, and duplicate Trash URL handling. |
| [library_navigation.md](library_navigation.md) | Modal navigation rail, header actions, compact Search button, and manual import dialog. |
| [library_maintenance.md](library_maintenance.md) | Daily network-independent RSS bookmark expiration and orphan cache cleanup. |
| [aicore.md](aicore.md) | Optional AI Analysis: AICore integration, prompt design, output parsing, quota handling, fallback. |
| [notifications.md](notifications.md) | Android notification channels and import/RSS result reporting. |
| [og_image_cache.md](og_image_cache.md) | Preview image cache and card image priority. |
| [domain_icon_cache.md](domain_icon_cache.md) | Domain icon recovery and caching. |
| [application_summary.md](application_summary.md) | Storage dashboard and cache management. |

### Conventions & Design

| Document | Contents |
| :--- | :--- |
| [coding_conventions.md](coding_conventions.md) | Naming, Compose performance, coroutines, error handling. |
| [ui_design.md](ui_design.md) | Material 3 Expressive foundation: color, typography, shape, motion, insets, chrome, theme surfaces. |
| [theme_development.md](theme_development.md) | Multi-theme surface tokens, preset authoring, and Haze backdrop blur integration. |
| [chrome_blur_performance.md](chrome_blur_performance.md) | Backdrop blur frame timings and GPU performance metrics. |
| [ui_library.md](ui_library.md) | Library/Search cards, deck grids, domain watermarks, view modes, and paging UI rules. |
| [ui_bookmark_detail.md](ui_bookmark_detail.md) | Bookmark Detail layout, hero banner/palette, tag edit dialog, and entity picker sheet. |
| [ui_settings.md](ui_settings.md) | Settings cards, Private Content settings, AI Analysis toggles, and Debug Web Parser UI. |

### Where to Start

| If you are working on... | Read |
| :--- | :--- |
| A new screen or component | `structure.md`, `ui_design.md` |
| Themes, surfaces, or backdrop blur | `theme_development.md`, `ui_design.md` |
| Library, Search, or bookmark cards | `ui_library.md`, `ui_design.md` |
| Bookmark Detail or tag editing | `ui_bookmark_detail.md`, `ui_design.md` |
| Settings or Debug screens | `ui_settings.md`, `ui_design.md` |
| Saving, direct import, or parsing | `overview.md`, `data_models.md`, `bookmark_classification.md` |
| RSS | `rss.md` |
| Collections | `collections.md` |
| Trash or daily maintenance | `trash.md`, `library_maintenance.md` |
| AI Analysis | `aicore.md` |
| Database changes | `data_models.md` |

---

## Core Principles

1. **Local-first**: Bookmarks, tags and usage data are stored on the device. There is no account, analytics SDK or sync server. Network access is limited to fetching the pages and feeds the user adds.
2. **AI is optional**: Every feature must work with AI Analysis disabled. AI enriches a bookmark with a summary, tags and keywords but is never required to save, find or read one.
3. **Opt-in external services**: Any future cloud AI must be off by default, use the user's own API key, and send data only after explicit consent.
4. **Material 3 Expressive UI**: Expressive shapes, spring motion, tonal surfaces, dynamic color and full edge-to-edge layout.
5. **Lifecycle-aware state**: Collect flows with `collectAsStateWithLifecycle()`.
6. **Non-blocking I/O**: Run heavy work (DOM parsing, serialization, disk I/O) on `Dispatchers.IO`.
