# UI Design & Material 3 Expressive Specification

Tidilo adheres to Google's **Material 3 Expressive** specification—organic spring physics, rounded shapes, tonal surface hierarchy, dynamic color, optional surface-local frost, and full Edge-to-Edge layouts.

## Screen Specifications Index
| Area | Specification |
| :--- | :--- |
| **Library, Search, Cards & Paging** | [ui_library.md](ui_library.md) |
| **Detail & Tag Editor** | [ui_bookmark_detail.md](ui_bookmark_detail.md) |
| **Settings, Private & Debug UI** | [ui_settings.md](ui_settings.md) |
| **Feature-Specific UI** | [library_navigation.md](library_navigation.md), [collections.md](collections.md), [trash.md](trash.md), [rss.md](rss.md), [application_summary.md](application_summary.md), [notifications.md](notifications.md) |
| **Theme Authoring & Blur Mechanics** | [theme_development.md](theme_development.md), [chrome_blur_performance.md](chrome_blur_performance.md) |

---

## 1. Color System & Semantic Tokens

Supports **Material You Dynamic Color** (Android 12+) with fallback M3 Expressive palettes:
- **Primary (`0xFF006874` Light / `0xFF4FD8EB` Dark)**: Key actions, brand identity, active states, primary counters.
- **Secondary (`0xFF4A6267` Light / `0xFFB1CBD0` Dark)**: Supporting controls, subtle chips.
- **Tertiary (`0xFF525E7D` Light / `0xFFBAC6EA` Dark)**: Entity tags, metadata pills, completed states.
- **Background / Surface (`0xFFF8FAF9` Light / `0xFF0E1415` Dark)**: Base canvas.
- **Surface Containers**: `surfaceContainerHighest` (floating pills), `surfaceContainerHigh` (cards), `surfaceVariant` (unfocused inputs, subtle washes).
- **Accent Tag Tokens**: Derived dynamically from `MaterialTheme.colorScheme` via `resolveChipColors` (`ThemeModifiers.kt`, `ThemeSurfaces.kt`).
- **Warning Colors (Semantic Amber/Gold)**: `WarningLight` (`0xFFB87700`), `WarningDark` (`0xFFFFBA28`), and `WarningColor.textOrIcon` (resolves via `isSystemInDarkTheme()`).

---

## 2. Typography Scale (`ui/theme/Type.kt`)

| Style | Size / Line Height | Weight | Usage |
| :--- | :--- | :--- | :--- |
| **displayLarge** | 44sp / 52sp | Bold | Hero banners |
| **headlineMedium** | 28sp / 36sp | SemiBold | Top screen titles (Library, Settings, Tag Selection) |
| **headlineSmall** | 24sp / 32sp | SemiBold | Section titles, prominent modals |
| **titleLarge** | 20sp / 26sp | SemiBold | Bookmark detail titles, dialog titles |
| **titleMedium** | 16sp / 24sp | Medium / SemiBold | Card titles, group headers, card labels |
| **bodyLarge** | 16sp / 24sp | Normal | Long descriptions, web article snippets |
| **bodyMedium** | 14sp / 20sp | Normal | Card descriptions, note body, history rows |
| **bodySmall** | 12sp / 16sp | Normal | URLs, secondary captions, timestamps |
| **labelLarge** | 14sp / 20sp | SemiBold / Bold | Pill buttons (Save, OK, Import, Clear) |
| **labelMedium** | 12sp / 16sp | Medium | Counters, status pills, Detail breadcrumbs |
| **labelSmall** | 11sp / 16sp | Medium | Badges, tag chips, metadata footers |

---

## 3. Shape Hierarchy (`ui/theme/Shape.kt`)

- **ExtraSmall (`8dp`)**: Tooltips, micro badges.
- **Small (`12dp`)**: `CompactTagBadge`, text fields, icon boxes.
- **Medium (`20dp`)**: Primary buttons, settings cards, deck cards.
- **Large (`24dp`–`28dp`)**: `BookmarkItemCard`, dialogs, parameter cards.
- **ExtraLarge / Pill (`CircleShape` / `36dp`)**: FAB-style pills and primary action buttons.

---

## 4. Motion & Animation Physics

- **Library Header Transitions**: `spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)` with `fadeIn(200ms)` / `fadeOut(150ms)`. Scrolling down slides the header out upward while Search stays fixed at `56.dp`.
- **Screen Navigation (`TidiloNavGraph.kt`)**:
  - Symmetric lateral slides (`NoBouncy`, `StiffnessMediumLow`): `SlideDirection.Start` on forward, `SlideDirection.End` on pop/predictive-back (overriding default `scaleOut(0.7f)`).
  - Evaluates direction dynamically via `targetState.id == navController.previousBackStackEntry?.id` so interrupted forward entrances reverse cleanly without depth mismatches.
  - `MainActivity` hosts one stationary `TidiloBackground` under a transparent root `Scaffold`; individual screens omit `.background()` to avoid GPU overdraw and seams.
- **In-Page Filter & Mode Transitions (`LibraryScreen.kt`)**: Crossfade (`fadeIn(220ms)` togetherWith `fadeOut(180ms)`).

---

## 5. Edge-to-Edge, Adaptive Insets & FAB Placement

- **Window & Scaffold Ownership**: `MainActivity` enables `enableEdgeToEdge()` and `adjustResize`, setting `contentWindowInsets = WindowInsets(0, 0, 0, 0)` on the root `Scaffold`. `TidiloScreenScaffold` receives `contentWindowInsets = screenSystemInsets()` (system bars + cutout), subtracts ancestor consumption, applies horizontal margins and header clearance, and adds bottom system insets only to app-owned `bottomContentPadding` (passed as `contentPadding` to lazy layouts, or after `verticalScroll` on columns).
- **Keyboard (IME) Protection**: Input bodies use `avoidIme = true` on `TidiloScreenScaffold`, owning one `imePadding` wrapper before scrolling body content (instead of an IME ruler or `safeGesturesPadding` alone) so lateral transitions preserve width. Backgrounds and headers never shrink for IME. Search clears focus/IME when entering Detail; Detail hides its `54.dp` lower toolbar (`31.dp` margin + `16.dp` gap) and reserve while IME is shown. Native sheets/dialogs keep Material3 inset and IME handling.
- **Scroll Bar Hiding (`onPostScroll`)**: Hides bars only on actual scroll consumption (`consumed.y < -30f`); shows on upward scroll (`consumed.y > +20f`) or top overscroll (`available.y > +20f`). While hidden, a 4-stop status bar scrim (`0.85f, 0.50f, 0.15f, Transparent`) protects system icons.
- **Floating Action Clearances (`screenActionInsets`)**: Library reserves `56dp + 24dp + 24dp` (`104dp`, fixed Search `28dp` icon); AI Review Success reserves `86dp + 16dp + 16dp`; RSS reserves `56dp + 16dp + 16dp`. Loading/error views without FABs reserve zero action space.
- **Expanding Sub-Menu FABs (`86dp` toggle)**: Child pills use `CircleShape` (`horizontal = 24.dp, vertical = 16.dp`), `28.dp` icons, `titleMedium` labels, `Alignment.End` alignment, `14.dp` item gaps, and `16.dp` toggle gap.

---

## 6. Shared Chrome & Header Geometry

- **`FrostedHeaderContainer` & Neutral Dissolve**: Uses stationary canvas + screen body source masked once via a remembered `ShaderBrush` (14-stop quintic smootherstep, `contentAlpha = 0.85f`, neutral tint only—no colored overlay) over `HeaderDefaults` (`80.dp` standard row height or baseline + `8.dp` at large fonts, `decayOverlap = 6.dp`, `fadeHeight = 18.dp`). Resting body content starts `6.dp` below (`statusBarTop + 104.dp` normally).
- **Hit Handling & Scroll Forwarding**: The non-interactive dissolve spacer passes pointer events through; the header body consumes taps (`detectTapGestures`) so obscured cards cannot trigger and forwards vertical drags/flings to the active `ScrollableState`.
- **Title Bar Standardization (`TidiloFrostedHeader`, `TidiloPageHeader`)**:
  - `20.dp` horizontal screen margin; `headlineMedium` Bold (`onBackground`); `48.dp` interactive navigation/action slots (`4.dp` trailing spacing -> `72.dp` title start when leading button exists, `20.dp` otherwise); `Icons.AutoMirrored.Filled.ArrowBack`.
  - Optional `onTitleClick` (`TextHandleMove` haptic, no ripple) on the weighted title column; Library scrolls the active list/grid to `0` and cancels pending scrolls on state changes.
  - Sub-screens with Back: `Search`, `Notification`, `BookmarkDetailScreen`, `TagSelectionScreen`, `SettingsScreen`, `PrivateSettingsScreen`, `SettingsDebugScreen`, `GenerateContentRequestScreen`, `StorageScreen`, `RssSettingsScreen`, `BookmarkAiReviewScreen`, plus Domain/RSS/Collection drill-downs.
- **Visibility & Draw Order**: Draw content first, header second, overlays last. Only Library and Search enable `hideHeaderOnScroll` via narrow `ScaffoldHeaderVisibility` (`LocalBarsVisible`); header disappearance never collapses body top clearance.

---

## 7. Multi-Theme Infrastructure & Shared Surface Contract

### 7.1 Centralized Stationary Background (`TidiloBackground.kt`)
Anchored beneath `TidiloNavGraph` in `MainActivity.kt` so route transitions slide only foreground chrome/content.

### 7.2 Ambient Lighting (`LocalAmbientLighting`, `RememberAmbientLighting`)
Drill-downs publish active colors (`ownerId = "library_drilldown"`) via `RememberAmbientLighting` (auto-resetting on `DisposableEffect` disposal); `TidiloBackground` morphs `effectiveActiveColor` in-place via `animateColorAsState(tween(350, EaseInOutCubic))`.

### 7.3 Resolved Theme and Shared Surface Contract
> For authoring presets, blur adapters, and verification harnesses, see [Developing App Themes](theme_development.md).

`TidiloTheme` resolves Material colors, Material shapes, and `LocalThemeSurfaces` together per `AppTheme` (`default` / `modern`) without recreating navigation, scroll, or editor state:
- `AppThemeStyle` defines optional light/dark palettes (preserving Dynamic Color when unspecified), `ThemeShapeScale` (`6/8/10/12/14/16/20/22/24/28/32/36dp`), and per-role `SurfaceStyleOverride` values.
- `ResolvedThemeSurfaces.resolve` enforces full foreground/container/edge/shadow tokens; semantic error, matched chip, and selected filter pairs supersede neutral overrides.
- `SurfaceTreatment` selects native vs. decorated drawing independently of elevation (`TidiloSurface` places shadows outside content/ripple clips; individual screens do not force containers transparent).

### 7.4 Component Coverage and Supported Parameters

| Consumer family | Theme connection | Behavior retained |
| :--- | :--- | :--- |
| Bookmark list/grid | `BOOKMARK_CARD`, compact grid variant | Scoped accent rim/wash/shadow, image crop, click/long press, paging keys |
| Collection/domain/RSS deck | `DECK_FRONT` and `DECK_BACK` | Front/back corner diffs, stack offsets, watermark, contextual wash |
| Classification/entity badges | Shared badge resolution and Surface | Category hierarchy, Private badge tones, search-match colors |
| Entity editor/toggle chips | `ENTITY_CHIP` / `FILTER_CHIP` compact native | Flat styling, selection/removal, animated colors, limit state |
| Navigation, toolbar & actions | `FLOATING_BAR`, `TOOLBAR`, `PRIMARY_ACTION`, `FILTER_ACTION`, `NAVIGATION_INDICATOR` | Visibility animations, ripple, input semantics |
| Like & toolbar icon plates | `LIKE_ACTION` / `TOOLBAR_ACTION` wrappers | Semantic foreground tints, native icon/toggle interaction |
| Grouped settings/RSS/params | `SETTINGS_GROUP` and variants | Flat Modern appearance, padding, disabled parameter styling |
| Notification history & errors | Material typography, dividers, error tone | Timestamps, history navigation, Retry/Delete menus |
| Header & AI bubble | Resolved header/bubble attributes | Header fade & drag forwarding; bubble rainbow border & tail |
| Dialogs & bottom sheets | Resolved native container/default shape | Material focus, keyboard, scrim, dismissal |
| Sort menu, inputs, status, plates | `MENU`, `INPUT`, `STATUS_BADGE`, `ICON_PLATE` | Native behavior and semantic content |
| Remaining controls & diagnostics | Preset palette and shared `ThemeShapeScale` | Native defaults, error/disabled/focus styling |

Native Material consumers inherit palette and shape defaults without supporting custom decorations; layout, spacing, typography, and asymmetric crops remain component-owned.

### 7.5 Modern Preset, Haze Backdrop Blur & Verification
- **Modern Fixed Palettes**: Dark (`#1D1F20` canvas, `#2B2C2D` containers, `#D4B06B` accent); Light (`#F5F1E9` canvas, `#FFFAF2` containers, `#886225` accent). Retains `3.dp` tactile depth, `26.dp` card / `12.dp` badge corners, and optional `1.dp` downward shadow on selected nav/screen/primary labels.
- **Backdrop Blur (Haze 2.1.0)**: Defaults ON with theme-policy override without mutating saved preference. Two isolated graphs avoid feedback: an app-wide modal source (`TidiloAlertDialog`, `TidiloModalBottomSheet`, `TidiloDropdownMenu`, `LibraryNavigationRail`) and per-screen chrome sources (`FrostedHeaderContainer`, `Library` Search, `Detail` toolbar/browser jump). Unsupported rendering/OFF settings keep ordinary opaque drawing cleanly.
