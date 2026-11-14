# Library & Search UI Specification

> Library navigation rail structure, search focus restoration, and notification list behavior are specified in [Library Navigation](library_navigation.md). Collection cards and editors are specified in [Manual Collections](collections.md).

---

## 1. Library Shell & Chrome Actions

- **Root Header**: Library displays its title, subtitle, the top-left modal navigation rail trigger (`ModalWideNavigationRail(hideOnCollapse = true)`), and a single right-side `48.dp` display-mode selector (`R.drawable.ic_dashboard_2_gear`).
- **Domain, RSS & Collection Headers**: Overview modes expose the rail menu icon; drill-downs crossfade its glyph to `Up` (`220ms`, `EaseInOutCubic`) inside the same persistent `48.dp` slot (`4.dp` trailing padding) while keeping button geometry, title position, and the display-mode action fixed. Click handling and accessible descriptions switch immediately with the active hierarchy.
- **Compact Search Action**: Fixed bottom-right `56.dp` circular `PRIMARY_ACTION` surface (`expanded = false`, `28.dp` Search icon) with `24.dp` margins plus `screenActionInsets`. It never resizes or shifts on scroll, hides during Summary Peek, and is absent on child screens (see [UI Design §5](ui_design.md#5-edge-to-edge-adaptive-insets--fab-placement)).

---

## 2. `BookmarkItemCard` (`BookmarkCard.kt`)

- **Shape & Container**: `RoundedCornerShape(24.dp)`. Light mode uses pure white with zero tonal elevation as an intentional lighting exception; dark mode preserves `surfaceContainerHigh`.
- **Shared Bookmark Lighting (`BookmarkCardStyle.kt`)**: 1dp accent rim and 4dp shadow shared across list and grid cards (including Search). Incident lighting sweeps top-right (`Offset(Float.POSITIVE_INFINITY, 0f)`) to bottom-left (`Offset(0f, Float.POSITIVE_INFINITY)`) without neutral outline blending:
  - **Light**: rim alpha `0.38` → `0.04`, inner wash `0.045` → `0`, ambient shadow `0.25` (brand), spot shadow `0.10` (black).
  - **Dark**: rim alpha `0.65` → `0.05`, no inner wash, ambient shadow `0.25` (brand), spot shadow `0.50` (brand). Follows the applied scheme luminance, including theme overrides.
- **Drawing Order & Scoped Accent**: Shadows draw outside rounded clipping, wash behind content, and rim over content so images never obscure the illuminated edge. Domain/RSS drill-downs pass their beam `domainColor` via `LocalCardAccentColor` scoped inside each animated destination; unspecified destinations default to `MaterialTheme.colorScheme.primary`.
- **Interactions**: `800ms` click debounce + `Lifecycle.State.RESUMED` navigation gate in `TidiloNavGraph` prevents duplicate Detail entries. Each card exposes a `20.dp` Like toggle inside a `48.dp` touch target without navigating to Detail.
- **Hierarchy**:
  1. Title (keyword-highlighted in Search) + Like button.
  2. URL (1 line, ellipsis).
  3. Description (`MaterialTheme.typography.bodyMedium`, keyword-highlighted in Search).
  4. Tag row: `FlowRow` of `[Category] > [Subcategory]` (+ `Icons.Default.Lock` `Private` badge when `bookmark.isPrivate == true`, `SurfaceTone.NORMAL`, `resolveChipColors` with `secondaryContainer`/`onSecondaryContainer` fallback), wrapping cleanly on narrow cards and `2f` font scales.
  5. Entity tags: `FlowRow` of `CompactTagBadge` chips with `#` icon.

---

## 3. `CompactTagBadge`

- **Geometry**: Fixed `26.dp` height (`Modifier.height(26.dp)`), vertically centered, `RoundedCornerShape(12.dp)`.
- **Layout**: `14.dp` vector icon + `6.dp` spacer + single-line label with `10.dp` horizontal padding.
- **Search Match Highlight**: Matching query tokens transition container and content to high-contrast tonal colors.

---

## 4. `BookmarkGridCard` & Summary Peek (`BookmarkGridCard.kt`, `TimeFormatter.kt`)

- **2-Column Grid**: `LazyVerticalGrid(GridCells.Fixed(2))` with `20.dp` screen horizontal padding, `8.dp` inter-column gap, and `10.dp` vertical row spacing.
- **Card Container**: `Surface` (`RoundedCornerShape(16.dp)`, `tonalElevation = 0.dp`, white in Light / `surfaceContainer` in Dark) with shared bookmark lighting (§2) and `combinedClickable(onClick, onLongClick)`.
- **Top Preview (`16f / 9f`)**: Clipped to top `16.dp` corners using `ContentScale.Crop`.
  - Cached OG image: decodes local WebP via `rememberOgImage`. Missing/corrupt previews with an OG URL recover on demand via shared 4-worker-limited background requests (see [OG Image Cache](og_image_cache.md)).
  - Absent/pending preview: domain accent gradient with cached domain icon (`36.dp`) or fallback rounded link icon (`24.dp` inside `36.dp` container), defaulting to theme `primary` when no domain exists. Icon network recovery waits for a completed OG miss (or starts immediately if no OG URL exists).
- **Bottom Info Area (`132.dp` fixed height)**: Padding `start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp`:
  1. **Domain**: `labelSmall` (`11.sp`, `lineHeight = 15.sp`, `outline`, 1-line ellipsis).
  2. **Title**: Reserved 3 lines (`minLines = 3, maxLines = 3`, `titleSmall`, `SemiBold`, ellipsis).
  3. **Relative Time**: Bottom-aligned via `Spacer(Modifier.weight(1f))` in `labelSmall` (`outline`), formatted by `TimeFormatter.kt`: `< 1m` (`Just now`), `1–59m` (`1 minute ago` / `%d minutes ago`), `1–23h` (`1 hour ago` / `%d hours ago`), `1–29d` (`1 day ago` / `%d days ago`), `>= 30d` (`yyyy/MM/dd`).
- **Summary Peek Overlay**: Long-pressing a grid card hides bars/header and shows a borderless `background.copy(alpha = 0.85f)` overlay (no blur, preserving crisp text) at normal top body clearance (`statusBars + 104.dp`) with `✨ AI Summary` badge and `bodyMedium` (`lineHeight = 24.sp`) summary text. Tap or Back dismisses it cleanly.

---

## 5. `LibraryViewMode` Dropdown Menu (`LibraryScreen.kt`, `LibraryViewMode.kt`)

- **Container**: Anchored to the single `48.dp` `ic_dashboard_2_gear` header button; `200.dp` width, `RoundedCornerShape(16.dp)`, `surfaceContainerHigh`, `tonalElevation = 6.dp`, wrapped with `CompositionLocalProvider(LocalContext, LocalConfiguration)` so popup windows retain in-app localization.
- **Items (`[Checkmark] [Icon] [Display Name]`)**:
  - `LIST`: `"List"` (`R.string.library_view_mode_list`), `R.drawable.ic_list`.
  - `GRID`: `"Grid"` (`R.string.library_view_mode_grid`), `R.drawable.ic_grid_view`.
  - Selected row renders `Icons.Default.Check` (`18.dp`, `primary`), `20.dp` `primary` icon, and `FontWeight.Bold` `primary` label; unselected rows reserve `Spacer(18.dp)` to prevent horizontal jitter, with `onSurfaceVariant` icon and `onSurface` label. Each item exposes `Modifier.semantics { selected = isSelected }`.
- **Scope**: Persisted via `LibraryViewModel.viewMode` (`tidilo_library_prefs`); applies uniformly to Newest First and drill-down views with `AnimatedContent` crossfade (`220ms` in / `180ms` out).

---

## 6. `LibraryDeckCard` (`DomainCard`, `RssFeedCard`) & Drill-Down

- **Deck Grid**: `LazyVerticalGrid(GridCells.Fixed(2))` with `12.dp` horizontal/vertical spacing and `20.dp` screen horizontal padding. Shared by Domain and RSS modes (`RssFeedCard` supplies feed title, publisher domain, saved count, and artwork/color).
- **3-Layer Stacked Card (`168.dp` total / `154.dp` per layer)**:
  - Layer 2 (back): `surfaceContainerLow`, `padding(start = 14.dp)`, `0.dp` top offset, border `dominantColor.copy(alpha = 0.18f)`.
  - Layer 1 (mid): `surfaceContainer`, `padding(start = 7.dp, end = 7.dp)`, `7.dp` top offset, `1.dp` shadow, border `0.28f`.
  - Layer 0 (front): `surfaceContainerHigh`, `padding(end = 14.dp)`, `offset(y = 14.dp)`, `2.dp` shadow, `RoundedCornerShape(20.dp)`, border `0.42f`, plus top-right `Brush.linearGradient(0.32f -> Transparent)` and `96.dp` `DomainWatermark` (`0.20f` alpha, `offset(16.dp, -6.dp)`).
- **Dark-Mode Calibration (`DomainIconManager.ensureDarkThemeLegibility`)**: Boosts blue/violet hues (`190°–275°`) to min value `0.92f` / saturation `0.58f`, and enforces min value `0.72f` across all hues for legible borders/glows on dark cards.
- **Deck Title & Count Badge**: Bold, max 3 lines with ellipsis, scaled by Unicode code-point count (`<= 10`: `titleMedium` 16/24sp; `11–18`: `titleSmall` 14.5/19sp; `>= 19`: `bodyMedium` 13.5/17sp), domain/publisher subtitle (`bodySmall`, `onSurfaceVariant`), and count pill (`dominantColor.copy(0.20f)`, `Icons.Default.Bookmark`, `"%d items"`).
- **Drill-Down Ambient Background & Header**:
  - Publishes `selectedDomainColor` to `LocalAmbientLighting` (`RememberAmbientLighting`, `ownerId = "library_drilldown"`) driving a 6-stop radiant diagonal beam (`0.32f -> 0.22f -> 0.13f -> 0.05f -> 0.015f -> Transparent`) and top-right radial bloom (`size.height * 0.72f`: `0.22f -> 0.13f -> 0.05f -> 0.012f -> Transparent`).
  - Drill-down single-line titles scale by code points (`<= 6`: `headlineMedium`; `7–9`: `headlineSmall` 23/28sp; `10–13`: `titleLarge` 20/25sp; `>= 14`: `titleMedium` 17/22sp) with `basicMarquee(initialDelayMillis = 2000)` on overflow. Subtitle count uses immutable destination presentation and seeds from known collection counts without an intermediate `Loading` label.
- **Transitions & Back Stack**: Domain/RSS/filter switches crossfade symmetrically (`220ms` in / `180ms` out, `EaseInOutCubic`); `BackHandler` returns to the overview grid (preserving `domainGridState`) after dismissing any Summary Peek, while entering a drill-down resets its list to index `0`.

---

## 7. `DomainWatermark`

- **Default Appearance**: `195.dp`, `ColorMatrix` 50% saturation (`0.50f`), `0.14f` alpha, `RoundedCornerShape(20.dp)`.
- **Resolution**: Shared across cards, deck grids, and Detail via `rememberDomainVisual` (`CacheVisualSource`); handles non-blocking cache hits and bounded on-demand recovery without restarting sibling icon producers (see [Domain Icon Cache](domain_icon_cache.md)).

---

## 8. Paged Bookmark Results

- **Keys & State Separation**: Timeline, domain/RSS drill-downs, and Search render `LazyPagingItems` with stable bookmark-ID keys. Initial loading, refresh error, and empty results are distinct states; initial loading never renders an empty-library message, and same-key Room invalidations remain mounted when items exist.
- **Directional Append/Prepend Feedback**: Supports retry after earlier or later pages evict; errors take precedence over another direction's spinner. Domain-card grids use domain aggregation state exclusively, keeping bookmark load/error feedback strictly on bookmark lists/grids.
- **Scope Leases & Scroll Retention**:
  - Library collects destination-owned domain/feed/mode Pagers; outgoing flows stay leased until exit transitions dispose, `SideEffect` reactivates returning A→B→A transitions, and Private Content visibility changes discard visible destinations immediately. List and grid remember separate scroll states; domain positions reset only when the selected filter changes.
  - Search retains its ViewModel-owned `PagingGeneration` flow, shares the Summary Peek overlay (dismissed on tap, back, or query change), and resets both list/grid positions non-suspendingly on query changes while preserving them when returning from Detail.
- **Detail & Tag Aggregates**: Detail and AI Review observe a single ID per ID/retry and navigate only after DB writes commit (preserving screen + retry on failure). Tag selection counts and Settings domain lists query all matching SQL rows independently of loaded pages.
