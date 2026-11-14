# Settings & Diagnostic UI Specification

> Dedicated feature settings also live in their domain specs: [Storage Dashboard](application_summary.md), [RSS Subscriptions](rss.md), [Android OS Notifications](notifications.md), and [Library Navigation](library_navigation.md) (in-app Notification list).

---

## 1. Settings Root (`SettingsScreen.kt`)

- **Groups**:
  - **General**: Language, Theme, Notification (opens OS system notification settings directly), AI Analysis, Private Content.
  - **Data Management**: Storage, RSS.
  - **Debug**: Web Parser, Prompt Test, etc.
- **Card & Row Styling**: `20.dp` rounded `surfaceContainerHigh` cards, `20.dp` row padding, `40.dp` circular `primaryContainer` / `onPrimaryContainer` icons, and inset `outlineVariant` dividers (`0.25` alpha). Navigation rows expose a single `Button` target with text wrapping inside a weighted column. Manual domain icon re-fetch is omitted; Cache clearing on Storage handles automatic recovery.

---

## 2. Private Content Settings (`PrivateSettingsScreen.kt`)

- **Navigation**: `Settings -> General -> Private Content` (`Icons.Default.Lock`), fixed header with `Icons.AutoMirrored.Filled.ArrowBack`.
- **Switch Cards (`20.dp` `surfaceContainerHigh`)**:
  - Flattened `Row` (`Surface(40.dp Icon)` + `Spacer(12.dp)` + `Column(Modifier.weight(1f))` + `Spacer(12.dp)` + `Switch`) with `padding(horizontal = 16.dp, vertical = 14.dp)` to maximize text width and avoid mid-character wrapping before the switch.
  - Attach `Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = ...)` to the enclosing `Row` and set `Switch(..., onCheckedChange = null)` for full-row touch and TalkBack label association.
  - **Show Private Content**: `AppConfig.isPrivateContentVisible` (default ON).
  - **Bookmark Private Content**: `AppConfig.isPrivateContentBookmarkable` (default ON).
- **Domain Management List**: Lists registered domains with a leading `Checkbox` and domain name (+ cached icon/watermark); tapping the row toggles Private domain status via `DomainPolicyManager`.

---

## 3. AI Analysis Settings (`SettingsScreen` -> `AI Analysis`)

- **Sections**: `Using AI Model`, `Automation`, `AICore error fallback`, and `AICore parameters` inside a fixed-header secondary page.
- **Model Selector**: Inline `AICore` and `Disable` radio rows (`>= 48.dp` high). Fresh installs default to `AICore` only when initial readiness returns `AVAILABLE` (else `Disable`); manual choices persist. Re-entering checks readiness without writing mode, downloading models, generating content, or recording usage; while checking/unavailable, `AICore` is disabled (`0.38` alpha) with status/error guidance while `Disable` stays enabled.
- **Conditional Sections**: `Automation` (single `Switch` row), `AICore error fallback` (`Disable`, `If the quota is exceeded`, `All AICore errors`), and request parameters appear only when `AICore` is selected. Switching to `Disable` animates them out (sliding upward 1/3 height, fading, shrinking vertically with `NoBouncy` / `MediumLow` springs) while preserving saved preferences; rows expand cleanly on large fonts above navigation clearance.
- **RSS Modal Coupling**: In RSS feed add/edit dialogs, both mode radios remain selectable even when global AI Analysis is `Disable`, displaying a `MaterialTheme.colorScheme.error` explanation directly beneath the radio group (hidden when global `AICore` is active).

---

## 4. Debug Web Parser

- **Body Text Modes**: Toggleable `Readability4J` (original extraction), `Jsoup Clean`, and `Prompt` (full ad-filtered `ParsedWebPage.promptBodyText` prior to `800`-char snippet truncation, excluding prompt instructions/metadata).
- **Controls & Resilience**: `Copy Body` copies exactly what the active mode displays. Filter chips wrap across rows on narrow widths or large font scales; detail blocks span full width above the copy button. HTTP error bodies remain inspectable and copyable without invoking preparation or AICore.
