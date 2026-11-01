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
| [data_models.md](data_models.md) | `BookmarkItem`, tags, taxonomy, queue state machine, Room schema and migrations. |

### Features

| Document | Contents |
| :--- | :--- |
| [rss.md](rss.md) | RSS/Atom subscriptions, scheduled fetching, Wi-Fi policy, Direct/AI modes, deduplication, retention. |
| [aicore.md](aicore.md) | Optional AI Analysis: AICore integration, prompt design, output parsing, quota handling, fallback. |
| [notifications.md](notifications.md) | Android notification channels and import/RSS result reporting. |
| [og_image_cache.md](og_image_cache.md) | Preview image cache and card image priority. |
| [domain_icon_cache.md](domain_icon_cache.md) | Domain icon recovery and caching. |
| [application_summary.md](application_summary.md) | Storage dashboard and cache management. |

### Conventions & Design

| Document | Contents |
| :--- | :--- |
| [coding_conventions.md](coding_conventions.md) | Naming, Compose performance, coroutines, error handling. |
| [ui_design.md](ui_design.md) | Material 3 Expressive spec: color, typography, shape, motion, components. |

### Where to Start

| If you are working on... | Read |
| :--- | :--- |
| A new screen or component | `structure.md`, `ui_design.md` |
| Saving, parsing or the import queue | `overview.md`, `data_models.md` |
| RSS | `rss.md` |
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

> [!NOTE]
> Some documents in this folder still describe AI Analysis as the primary flow. They are being updated to match the principles above. Where they disagree, the principles above apply.
