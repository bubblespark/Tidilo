# Tidilo Technical Documentation

Welcome to the **Tidilo Technical Specification and Architecture Documentation**.  
This documentation provides developers with a clear, authoritative blueprint of the application's architecture, conventions, and implementation guidelines.

---

## Documentation Index

| Document | Topic | Key Contents |
| :--- | :--- | :--- |
| **[overview.md](overview.md)** | **Overall Architecture & Vision** | App purpose, high-level MVVM/UDF architecture, on-device AI integration, offline-first design, system boundaries. |
| **[tech_stack.md](tech_stack.md)** | **Tech Stack & Dependencies** | Exact SDK versions, Jetpack Compose BOM, ML Kit GenAI (AICore), Jsoup, Readability4j, Kotlin 2.1, AGP 8.7. |
| **[structure.md](structure.md)** | **Directory Structure & Modules** | Complete folder hierarchy, package responsibilities, layer boundaries, rules for adding new components. |
| **[ui_design.md](ui_design.md)** | **UI Design & M3 Expressive** | Material 3 Expressive guidelines, Color tokens, Typography, Shape system, Spring animations, Edge-to-Edge insets, Scrims. |
| **[data_models.md](data_models.md)** | **Data Models & State Flows** | `BookmarkItem`, `Tag`, `Taxonomy` (7 Categories, 11 Subcategories), `QueueItem` state machine, JSON schemas, Flow lifecycles. |
| **[aicore.md](aicore.md)** | **AICore & Prompt Engineering** | Gemini Nano on-device AI architecture, prompt rules, model options, summary tuning, output parsing pipeline. |
| **[rss.md](rss.md)** | **RSS and Atom Subscriptions** | Wi-Fi-aware scheduling, XML metadata, Direct/AI modes, receipt deduplication, source filters, and count retention. |
| **[coding_conventions.md](coding_conventions.md)** | **Coding Conventions & Best Practices** | Recomposition control, `collectAsStateWithLifecycle`, lazy item keys, coroutine threading, naming rules, error safety. |

---

## Core Principles

1. **On-Device Privacy & Offline First**: Tidilo processes web bookmarks locally on Pixel devices using Android's system AICore and Gemini Nano. No personal browsing data leaves the device.
2. **Material 3 Expressive UI**: Follow expressive curves (20dp–28dp, CircleShape pills), fluid spring motion physics, tonal surfaces, and dynamic color.
3. **Smooth Edge-to-Edge Immersion**: All screens must render edge-to-edge with correct IME, navigation bar, and status bar padding handling.
4. **Lifecycle-Aware State Collection**: Always use `collectAsStateWithLifecycle()` to prevent background battery drain.
5. **Asynchronous Non-blocking I/O**: Heavy operations (DOM parsing, JSON serialization, SharedPreferences) must execute on `Dispatchers.IO`.
