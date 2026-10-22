# Tech Stack & Dependency Specification

This document provides the canonical catalog of all programming languages, SDKs, tools, and third-party libraries used in **Tidilo**, along with exact version numbers and technical rationales.

---

## 1. Toolchain & Platform Versions

| Component | Version | Configuration Location | Purpose |
| :--- | :--- | :--- | :--- |
| **Android Gradle Plugin (AGP)** | `9.4.0` | `gradle/libs.versions.toml` | Build orchestration and packaging with built-in Kotlin support. |
| **Gradle Wrapper** | `9.8.1` | `gradle/wrapper/gradle-wrapper.properties` | Modern build automation tool. |
| **Kotlin** | `2.4.21` | `gradle/libs.versions.toml` | Core application language (AGP built-in). |
| **Kotlin Compose Compiler Plugin** | `2.4.21` | `gradle/libs.versions.toml` | Integrated Compose compiler plugin. |
| **KSP** | `2.3.12` | `gradle/libs.versions.toml` | Kotlin Symbol Processing for Room code generation. |
| **Java / JVM Target** | `21` | `app/build.gradle.kts` | Modern Java runtime bytecode target. |
| **compileSdk** | `37` | `app/build.gradle.kts` | API 37 platform headers. |
| **minSdk** | `34` | `app/build.gradle.kts` | Android 14 baseline for AICore support. |
| **targetSdk** | `36` | `app/build.gradle.kts` | Target Android 15/16 platform behaviors. |

---

## 2. Jetpack Compose & AndroidX Libraries

| Library | Version / BOM | Package / Alias | Purpose |
| :--- | :--- | :--- | :--- |
| **Compose BOM** | `2026.09.00` | `androidx-compose-bom` | Dependency management across Compose UI modules. |
| **Compose UI & Foundation** | BOM managed | `androidx-ui`, `androidx-ui-graphics` | Declarative UI rendering, touch handling, layout primitives. |
| **Compose Material 3** | BOM managed (`1.4.0`) | `androidx-material3` | Material 3 components and design tokens. |
| **Material Icons Extended** | BOM managed | `androidx-material-icons-extended` | Material icons (Search, History, OpenInNew, Tag, Folder, etc.). |
| **Navigation Compose** | `2.10.2` | `androidx-navigation-compose` | In-app screen routing, type-safe arguments, animated transitions. |
| **Lifecycle Runtime KTX** | `2.11.0` | `androidx-lifecycle-runtime-ktx` | Android lifecycle coroutines and scopes. |
| **Lifecycle ViewModel KTX** | `2.11.0` | `androidx-lifecycle-viewmodel-ktx` | `viewModelScope` lifecycle-bound coroutine launch. |
| **Lifecycle Runtime Compose** | `2.11.0` | `androidx-lifecycle-runtime-compose` | `collectAsStateWithLifecycle()` for zero battery drain in background. |
| **Lifecycle ViewModel Compose** | `2.11.0` | `androidx-lifecycle-viewmodel-compose` | Compose integration for ViewModel instantiation (`viewModel()`). |
| **Activity Compose** | `1.13.0` | `androidx-activity-compose` | `ComponentActivity.setContent`, `enableEdgeToEdge()`, `BackHandler`. |
| **Room** | `2.8.5` | `androidx-room-runtime`, `androidx-room-ktx`, `androidx-room-compiler` | SQLite persistence, reactive DAOs, and flow queries. |
| **Core KTX** | `1.19.1` | `androidx-core-ktx` | Kotlin extensions for core Android platform APIs. |

---

## 3. On-Device AI & Web Scraping Libraries

| Library | Exact Artifact Coordinates | Purpose & Justification |
| :--- | :--- | :--- |
| **Google ML Kit GenAI Prompt SDK** | `com.google.mlkit:genai-prompt:1.0.0-beta2` | Directly binds to Android AICore system service to run **Gemini Nano** on-device. Zero network traffic, zero API keys required, instant response. |
| **Jsoup** | `org.jsoup:jsoup:1.18.3` | High-performance HTML parsing, DOM traversal, meta tag extraction, OGP detection, and HTML sanitization. |
| **Readability4J** | `net.dankito.readability4j:readability4j:1.0.8` | Kotlin port of Mozilla Firefox's Readability algorithm. Strips navigation bars, footers, and ads to extract clean article text. |

---

## 4. Test Libraries

| Library | Version | Purpose |
| :--- | :--- | :--- |
| **JUnit 4** | `4.13.2` | Core JVM unit testing framework for repositories, ViewModels, parsers. |
| **org.json** | `20240303` | Mock JSON implementations for pure JVM unit tests without Android runtime stubs. |

## Issue #81 database paging dependencies

- Paging runtime and Compose: `3.5.3`, pinned through the version catalog.
- Room Paging adapter: `2.8.5`, matching the existing Room runtime/compiler.
- Android integration test runner: `1.7.0`; AndroidX JUnit extension: `1.3.0`.
- [Paging release notes](https://developer.android.com/jetpack/androidx/releases/paging)
  document the selected release. Actual dependency resolution, debug compilation,
  and connected Android SQLite tests pass with the project's current toolchain.
