# Library & Search UI Specification

> Library navigation rail structure, search focus restoration, and notification list behavior are specified in [Library Navigation](library_navigation.md). Collection cards and editors are specified in [Manual Collections](collections.md).

---

## 1. Library Shell & Chrome Actions

- **Root Header**: Library displays its title, subtitle, the top-left modal navigation rail trigger (`ModalWideNavigationRail(hideOnCollapse = true)`), and a single right-side `48.dp` display-mode selector (`R.drawable.ic_dashboard_2_gear`).
- **Domain, RSS & Collection Headers**: Overview modes expose the rail menu icon; drill-downs crossfade its glyph to `Up` (`220ms`, `EaseInOutCubic`) inside the same persistent `48.dp` slot (`4.dp` trailing padding) while keeping button geometry, title position, and the display-mode action fixed. Click handling and accessible descriptions switch immediately with the active hierarchy.
- **Newest First Source Scope (`NewestFirstScopeRow.kt`)**: One Material 3 `FilterChip` labeled **Include RSS** / **RSSを含む** is the first scrolling item of Newest first, before bookmark cards, in List and Grid; it scrolls away with the content and is not a pinned toolbar. In Grid it occupies a full-width span. Its row height is the larger of the `48.dp` touch target and the single-line `labelLarge` height, and the Grid height budget includes it. Colors resolve from `SurfaceRole.FILTER_CHIP` (`SurfaceVariant.COMPACT`); the selected chip shows a leading check icon and exposes the checkbox role and selected state. The same row stays at the top of the Newest first empty, initial-loading, and refresh-error states. Unselected (default) shows manual bookmarks only (`rssFeedId IS NULL`, including browser-share imports and promoted RSS articles); selected adds RSS articles in the same `createdAt DESC, id DESC` result. The choice persists as `library_newest_include_rss` in `tidilo_library_prefs`, is shared by List and Grid, and survives restart, filter-mode changes, and Detail return. A scope change resets both Newest first scroll states to the top; Detail return keeps the position. The subtitle reads **Bookmarks (n)** / **ブックマーク (n)** or, while selected, **Bookmarks & RSS (n)** / **ブックマークとRSS (n)**. While selected, RSS articles show an `RssFeed` + **RSS** badge in the List tag row and an `RssFeed` icon (described as **RSS article** / **RSS記事**) at the end of the Grid domain row. Domain, RSS, Collection, and Search keep their own source scopes.
- **Radial Shortcuts**: Fixed bottom-right `56.dp` circular trigger (`28.dp` orbit/Close glyph, `24.dp` margins plus independent system/cutout insets). Tap opens five shortcuts; swipe opens immediately for continuous release selection, without a hold timer. Four filters occupy the outer upper-left arc and Search the inner diagonal. The same-window overlay dims and isolates background input; a single localized hover name and entry haptics identify targets without enlargement. It hides during Summary Peek/collection editing and is absent on child screens. See [Library navigation](library_navigation.md#library-and-rail) for geometry, cancellation, Back, accessibility, and filter behavior.

---

## 2. `BookmarkItemCard` (`BookmarkCard.kt`)

- **Shape & Container**: `RoundedCornerShape(24.dp)`. Light mode uses pure white with zero tonal elevation as an intentional lighting exception; dark mode preserves `surfaceContainerHigh`.
- **Shared Bookmark Lighting (`ThemeSurfaces.kt`, `TidiloSurface.kt`, `BookmarkCardStyle.kt`)**: `ThemeSurfaces.kt` (`SurfaceRole.BOOKMARK_CARD`) and `TidiloSurface.kt` (`rememberAccentBrush`) implement the 1dp accent rim and 4dp shadow shared across list and grid cards (including Search), sweeping top-right (`Offset(Float.POSITIVE_INFINITY, 0f)`) to bottom-left (`Offset(0f, Float.POSITIVE_INFINITY)`) without neutral outline blending:
  - **Light (`Default`, non-decorated)**: rim alpha `0.38` → `0.04`, inner wash `0.045` → `0`, ambient shadow `0.25` (brand), spot shadow `0.10` (black).
  - **Dark (`Default`, non-decorated)**: rim alpha `0.65` → `0.05`, no inner wash, ambient shadow `0.25` (brand), spot shadow `0.50` (brand).
  - **`Modern` (`SurfaceTreatment.DECORATED`, `showAccentRim = false`)**: omits the rim, inner wash, and 4dp accent ambient shadow on unspecified cards; when a scoped `accent` is supplied (Domain/RSS drill-downs), draws the tactile rim (`0.45` → `0.04` Light / `0.70` → `0.08` Dark) without inner wash.
- **Drawing Order & Scoped Accent**: Shadows draw outside rounded clipping, wash behind content, and rim over content so images never obscure the illuminated edge. Domain/RSS drill-downs pass their beam `domainColor` via `LocalCardAccentColor` (`BookmarkCardStyle.kt`) scoped inside each animated destination; unspecified destinations default to `MaterialTheme.colorScheme.primary`.
- **Interactions**: `800ms` click debounce + `Lifecycle.State.RESUMED` navigation gate in `TidiloNavGraph` prevents duplicate Detail entries. Each card exposes a `20.dp` Like toggle inside a `48.dp` touch target without navigating to Detail.
- **Hierarchy**:
  1. Title (up to 3 lines, ellipsis on overflow, keyword-highlighted in Search) + Like button.
  2. URL (1 line, ellipsis).
  3. Saved summary (`MaterialTheme.typography.bodyMedium`, up to 3 lines with ellipsis on overflow, keyword-highlighted in Search). Blank summaries leave no space.
  4. Tag row: `FlowRow` of `[Category] > [Subcategory]` (+ `Icons.Default.Lock` `Private` badge when `bookmark.isPrivate == true`, `SurfaceTone.NORMAL`, `resolveChipColors` with `secondaryContainer`/`onSecondaryContainer` fallback), wrapping cleanly on narrow cards and `2f` font scales.
  5. Entity tags wrap in saved order into at most 3 rows. The localized remaining count fits inside those rows and counts omitted chips. Long labels can ellipsize within a visible chip. Open the card to see the full text and all tags in Detail.

Search List places one localized caption above the card inside the same keyed paging item. It shows only the matching field names in the order Title, Summary, URL, Classification, and Entity tags. It reflects the query that loaded the row, including matches outside the preview. The caption wraps at narrow widths or large font sizes. A query with no effective terms leaves no caption.

---

## 3. `CompactTagBadge`

- **Geometry**: Vertically centered, `RoundedCornerShape(12.dp)`, with a standard `26.dp` height. List entity badges use `26.dp` as a minimum so enlarged text can fit.
- **Layout**: `14.dp` vector icon + `6.dp` spacer + single-line label with `10.dp` horizontal padding.
- **Search Match Highlight**: Matching query tokens transition container and content to high-contrast tonal colors.

---

## 4. `BookmarkGridCard` & Summary Peek (`BookmarkGridCard.kt`, `TimeFormatter.kt`)

- **Adaptive Bookmark Grid**: Newest First, Domain/RSS/Collection bookmark results, and Search Grid use the same sizing policy with `20.dp` screen horizontal padding, an `8.dp` inter-column gap, and `10.dp` row spacing. The preferred card width accounts for the scaled title font and text padding. Two columns are preferred when both cards fit, and narrow windows or larger text can reduce the Grid to one column. Short windows can add readable columns to fit the first card. The height budget includes system insets, header clearance, the Search input height derived from the single-line text metrics and field padding, the Newest first scope row, and the Library shortcut where it overlaps the leading column. Each destination keeps separate List and Grid scroll states. When a resize changes the column count or shortens the media frame, or the text configuration changes, the grid uses the leading loaded bookmark's key to restore its row. A query or filter change resets the associated scroll position.
- **Card Container**: `TidiloSurface(role = SurfaceRole.BOOKMARK_CARD, variant = SurfaceVariant.COMPACT, accent = LocalCardAccentColor.current)` (`RoundedCornerShape(16.dp)`, white in Light / `surfaceContainer` in Dark in `Default`) with shared bookmark lighting (§2) and `combinedClickable(onClick, onLongClick)`.
- **Top Preview**: Uses `16:9` framing when it fits. The grid caps the frame in a short viewport after trying additional readable columns. Artwork and fallbacks share that frame, and artwork uses `ContentScale.Crop` with the card's top corners. The upper-right Like target stays `48.dp` and can extend beside the domain when the preview is shorter. The preview stays at least `24.dp` high, and the card can scroll when the available viewport is shorter than its content.
  - Cached OG image: decodes local WebP via `rememberOgImage`. Missing/corrupt previews with an OG URL recover on demand via shared 4-worker-limited background requests (see [OG Image Cache](og_image_cache.md)).
  - Absent/pending preview: domain accent gradient with cached domain icon (`36.dp`) or fallback rounded link icon (`24.dp` inside `36.dp` container), defaulting to theme `primary` when no domain exists. Icon network recovery waits for a completed OG miss (or starts immediately if no OG URL exists).
- **Bottom Info Area**: The active text styles and font scale determine the minimum heights of the domain, title, and relative-time slots. The area uses `12.dp` horizontal padding, `8.dp` top padding, `12.dp` bottom padding, and a `4.dp` gap above and below the title.
  1. **Domain**: `labelSmall` (`11.sp`, `lineHeight = 15.sp`, `outline`, 1-line ellipsis). When Newest first includes RSS, an RSS article adds a `primary` `RssFeed` icon sized to the domain slot height at the row end; the domain ellipsizes to keep the icon and the Like clearance.
  2. **Title**: Reserved 3 lines (`minLines = 3, maxLines = 3`, `titleSmall`, `SemiBold`, ellipsis).
  3. **Relative Time** uses `labelSmall` with the `outline` color and can wrap at narrow widths. `TimeFormatter.kt` formats `< 1m` (`Just now`), `1–59m` (`1 minute ago` / `%d minutes ago`), `1–23h` (`1 hour ago` / `%d hours ago`), `1–29d` (`1 day ago` / `%d days ago`), and `>= 30d` (`yyyy/MM/dd`).
- **Summary Peek Overlay**: Long-press a grid card with a saved description in Library or Search to read it under the localized **Summary** / **要約** heading and document icon. The borderless preview sits at normal top body clearance (`statusBars + 104.dp`) and uses `bodyMedium` with `24.sp` line height. With blur enabled, the screen's own card backdrop is blurred at `20.dp` and covered by a neutral theme tint. The heading, icon, and text stay sharp. Blur OFF or unsupported rendering uses the theme background at 85% opacity. If an enabled capture temporarily disappears, the preview uses the opaque theme background.
- **Summary Peek Interaction**: Scroll to read, then tap, press Back, or use the accessible Close action. The preview takes over accessibility traversal while open and returns to the same underlying grid position after dismissal. Opening records the existing summary-peek engagement event and reads the saved description directly.

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
- **Deck Title & Count Badge**: Bold, max 3 lines with ellipsis, scaled by Unicode code-point count (`<= 10`: `titleMedium` 16/24sp; `11–18`: `titleSmall` 14.5/19sp; `>= 19`: `bodyMedium` 13.5/17sp), domain/publisher subtitle (`bodySmall`, `onSurfaceVariant`; hidden with its spacer when blank or equal to the title ignoring case and surrounding space, `deckSubtitle`), and count pill (`dominantColor.copy(0.20f)`, `Icons.Default.Bookmark`, `<plurals>` `domain_card_item_count`).
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

---

## 9. Library Layer (Discover preview)

Shown only while `AppConfig.discoverPreviewEnabled` is on (`LibraryScreen(discoverPreview = true)`); flag off keeps §1–§8 unchanged. Design spec §9.

- **Header**: title **Library** on the four tab roots, the per-tab count subtitle (§1), and the rail trigger. Under the title row, `LibraryTabs` (`LibraryTabs.kt`): a connected button group of four `ToggleButton`s (Newest / Domain / RSS / Collection; **新着 / ドメイン / RSS / コレクション**) with `ButtonGroupDefaults.connected*ButtonShapes()` (the checked segment becomes a full pill), `surfaceContainerHigh` / `secondaryContainer` colors, `48.dp` height, `selectableGroup`, tags `library_tabs` and `library_tab_<MODE>`. The group lives inside the frosted header, so it hides and returns with it on scroll; `TidiloScreenScaffold(headerExtraHeight = LibraryTabsHeight)` adds its height to the content's top clearance. Drill-downs hide the group and keep the Up glyph and the item's title.
- **View toggle**: one `48.dp` icon button (`library_view_toggle`, **Show as list** / **Show as grid**) replaces the display-mode dropdown on Newest and drill-downs; deck tabs have none. The choice is the same persisted `LibraryViewMode`.
- **Grid** (`LibraryPlateCard`, `LibraryPlateCards.kt`): `LazyVerticalGrid(GridCells.Adaptive(160.dp))`, `16.dp` × `20.dp` gaps, content type `library_plate_card`. Each card is a 1.91:1 plate (`DiscoverPlate`, `shapes.medium`; a poster without og:image) with the domain (`labelMedium`), the title (`titleSmall`, 3 lines), and the relative time below it, never on the art. With Include RSS on, RSS articles add an `RssFeed` glyph before the time and "RSS" to the description. Scroll position comes from the saveable grid state (no radial-menu anchor).
- **List** (`LibraryPlateRow`): XS plate (`112.dp`, initial-only poster), title (2 lines), and "domain · time"; content type `library_plate_row`.
- **Cards**: tap opens Detail; long-press opens the Peek sheet (`onLongClickLabel` **Peek**). Each card merges one description (title, domain, time, RSS). Tags `library_card_<id>` / `library_row_<id>`. Drill-downs use the same cards. Cards have no heart button; Like is in the Peek sheet and Detail.
- **Peek sheet** (`LibraryPeek.kt`): the Discover Peek sheet with **Like** and **Add to collection** only (no Not now / Don't show again). The bookmark is observed from Room, so Like shows the stored state and a deleted bookmark closes the sheet. Opening records `PEEK` with `EngagementSurface.LIBRARY`. The peeked id and its collection sheets survive rotation (`rememberSaveable`). Summary Peek is not shown.
- **Decks** (`LibraryDeckCard(gallery = true)`): three tonal layers (`surfaceContainerLow` / `surfaceContainer` / `surfaceContainerHigh`), `1.dp` accent hairlines (α 0.32 back, 0.5 front), an accent wash on the front, `shapes.largeIncreased`, the favicon watermark, a 2-line title, the subtitle rule of §6, and the plural count pill. The deck is `166.dp` tall at 1× font and grows `100.dp` per extra 1× of font scale so the pill never collapses. Plate cards, decks, and collection cards expose `Role.Button`. Collection keeps its 1-column card with the same tonal layers and hairlines (`CollectionCard(gallery = true)`).
- **Empty states** (`LibraryEmptyState`, tag `library_empty_<MODE>`, button `library_empty_action`): icon, `titleLarge` heading, body, and a filled button.

| Tab | Title | Action |
| :--- | :--- | :--- |
| Newest | **No bookmarks yet** (below the Include RSS chip) | **Save URL** → Manual Import |
| Domain | **No sites yet** (Domain lists manual saves only) | **Save URL** |
| RSS, no subscriptions | **Get updates from sites you read** | **Add feed** → RSS Settings |
| RSS, subscribed but nothing visible (not fetched yet, all Private while hidden, or expired) | **No articles to show right now** | **RSS settings** → RSS Settings |
| Collection, none | **Group bookmarks by theme** | **New collection** → editor sheet; the dashed add card appears only once a collection exists |

The RSS branch reads `LibraryViewModel.rssSubscriptionCount` (non-deleted rows of `rss_feeds`). While that count is unknown, the RSS tab keeps its loading skeleton instead of guessing an empty state. An empty drill-down uses the Newest state.

