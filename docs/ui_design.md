# UI Design & Material 3 Expressive Specification

Overlay backgrounds support optional surface-local frost with a theme-derived tint and 1dp outline. Text and controls remain sharp, and the ordinary scrim remains outside the surface. The independent preference defaults to ON; theme policies can permit user choice or force either state without replacing the saved preference.

| Overlay family | Shared integration |
| --- | --- |
| App-owned alert dialogs | `TidiloAlertDialog` |
| Modal bottom sheets | `TidiloModalBottomSheet` |
| Popup menus | `TidiloDropdownMenu` |
| Library modal navigation rail | `LibraryNavigationRail` with the shared renderer |
| Shared page headers | Body-only screen source and smootherstep mask in `FrostedHeaderContainer` |
| Library Search | Opt-in `TidiloPrimaryActionSurface` background |
| Detail floating toolbar and browser jump | Opt-in TOOLBAR and compact PRIMARY_ACTION backgrounds |

OFF retains native theme drawing. Unsupported rendering retains ordinary theme drawing; unavailable captured sources retain a readable fallback. Headers fade both blur and tint to transparency without a bottom outline; bounded floating controls use the theme tint and 1dp outline. Ordinary cards, other toolbars, screen backgrounds and OS-owned UI are not blur targets. Sheet/dialog title rows do not receive a second effect. See [overlay integration](theme_development.md#add-a-blur-enabled-overlay) and [screen chrome integration](theme_development.md#add-blur-to-in-window-screen-chrome) for ownership, extension APIs, policies and validation.

Trash list rows, overflow actions and confirmations are specified in [Trash](trash.md). Rows have no tap action; they use the existing header and shared dialog/menu surface roles with explicit theme foreground colors.

The Collection library, prototype-derived horizontal cards, editor and bookmark assignment sheets are specified in [Manual Collections](collections.md).

OS notification channels and the Settings > Notification system-settings entry are specified in [Android OS Notifications](notifications.md). The entry opens system settings directly, without an intermediate dialog. The Library rail opens the separate in-app Notification list; see [Library Navigation](library_navigation.md).

Tidilo strictly adheres to Google's **Material 3 Expressive** design specification, emphasizing organic fluid motion, prominent rounded shapes, expressive typography, tonal surface hierarchy, and deep Edge-to-Edge immersion.

---

## 1. Color System & Semantic Tokens

The color system supports both **Material You Dynamic Color** (on Android 12+ / Pixel hardware) and custom M3 Expressive palettes for fallback.

### 1.1 Color Palette
- **Primary (`0xFF006874` Light / `0xFF4FD8EB` Dark)**: Expressive teal/cyan for key actions, brand identity, active states, and primary counters.
- **Secondary (`0xFF4A6267` Light / `0xFFB1CBD0` Dark)**: Balanced secondary slate for supporting UI elements, secondary tags, and subtle chips.
- **Tertiary (`0xFF525E7D` Light / `0xFFBAC6EA` Dark)**: Soft blue-violet for entity tags, metadata pills, and completed task states.
- **Background / Surface (`0xFFF8FAF9` Light / `0xFF0E1415` Dark)**: Ultra-clean base canvas ensuring deep contrast.
- **Surface Containers**:
  - `surfaceContainerHighest`: Base container for floating pill bars (navigation bar).
  - `surfaceContainerHigh`: Base container for bookmark cards (elevates content without harsh drop shadows).
  - `surfaceVariant`: Unfocused text field containers, subtle borders, inactive pills.

### 1.2 Accent Tag Tokens
Visual categorization chips dynamically derive container and content colors from `MaterialTheme.colorScheme` tokens via `resolveChipColors` (`ThemeModifiers.kt`) and `ThemeSurfaces.kt` rather than static hardcoded color constants, adapting seamlessly across dynamic light and dark themes.

### 1.3 Warning Colors (Semantic Amber/Gold)
Used for warning indicators and non-blocking fallback statuses:
- **`WarningLight` (`0xFFB87700`)**: Brightened amber-gold tone calibrated for clear yellow/warning icon recognition and high legibility against light backgrounds.
- **`WarningDark` (`0xFFFFBA28`)**: Luminous gold/yellow tone ensuring vibrant visibility and high contrast against dark OLED/gray surfaces.
- **`WarningColor.textOrIcon`**: Adaptive `@Composable` helper dynamically resolving to `WarningDark` when `isSystemInDarkTheme() == true` and `WarningLight` otherwise.

---

## 2. Typography Scale

Configured in `ui/theme/Type.kt` using standard font scaling with expressive letter spacing:

| Style | Size / Line Height | Weight | Usage |
| :--- | :--- | :--- | :--- |
| **displayLarge** | 44sp / 52sp | Bold | Expressive hero banners |
| **headlineMedium** | 28sp / 36sp | SemiBold | Top screen titles (Library, Settings, Tag Selection) |
| **headlineSmall** | 24sp / 32sp | SemiBold | Section titles, prominent modals |
| **titleLarge** | 20sp / 26sp | SemiBold | Bookmark detail titles, dialog titles |
| **titleMedium** | 16sp / 24sp | Medium / SemiBold | Bookmark card titles, group headers, card labels |
| **bodyLarge** | 16sp / 24sp | Normal | Long descriptions, web article text snippets |
| **bodyMedium** | 14sp / 20sp | Normal | Card descriptions, notes editor body, history rows |
| **bodySmall** | 12sp / 16sp | Normal | URLs, secondary captions, metadata timestamps |
| **labelLarge** | 14sp / 20sp | SemiBold / Bold | Pill buttons (Save, OK, Import, Clear) |
| **labelMedium** | 12sp / 16sp | Medium | Item counters, status pills, sub-headers |
| **labelSmall** | 11sp / 16sp | Medium | Badges, tag chips, chevron arrows |

---

## 3. Shape Hierarchy

Defined in `ui/theme/Shape.kt`:

- **ExtraSmall (`8dp`)**: Tooltips, micro badges.
- **Small (`12dp`)**: Compact tag badges (`CompactTagBadge`), text field inputs, icon container boxes.
- **Medium (`20dp`)**: Expressive interactive buttons (OK, Save, More Items), settings cards.
- **Large (`24dp` - `28dp`)**: Bookmark cards (`BookmarkItemCard`), dialog containers, parameter cards.
- **ExtraLarge / Pill (`CircleShape` / `36dp`)**: Fab-style pills and primary action buttons.

---

## 4. Motion & Animation Physics

Tidilo uses realistic spring physics rather than mechanical linear transitions:

### 4.1 Library Header Transitions
- **Animation Spec**: `spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)`
- **Behavior**:
  - When scrolling down (upward swipe): The Library header slides out upward; its Search action stays visible at 56dp.
  - When scrolling up (downward swipe): The Library header slides in from above; Search does not resize or move.
  - Combined with subtle alpha fade (`fadeIn(200ms)` / `fadeOut(150ms)`).

### 4.2 Screen-to-Screen Navigation & In-Page State Transitions
  - **Screen-to-Screen Transitions (`TidiloNavGraph.kt`)**:
    - Fully symmetric lateral slide transitions with non-bouncy spring physics (`dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow`):
      - **Forward Hierarchical Navigation (Enter / Exit)**: Incoming screen slides in from right to left (`SlideDirection.Start`); outgoing screen slides out toward left (`SlideDirection.Start`).
      - **Backward / Pop Hierarchical Navigation (PopEnter / PopExit)**: Previous screen slides in from left to right (`SlideDirection.End`); popping screen slides out toward right (`SlideDirection.End`).
      - **Predictive Pop Navigation (PredictivePopEnter / PredictivePopExit)**: Explicitly overrides Navigation Compose default `scaleOut(0.7f)` with symmetric lateral slides (`slideIntoContainer(End)` and `slideOutOfContainer(End)`). Completely eliminates unwanted shrinking-to-center / dissolve artifacts during predictive back swipe gestures.
  - **Dynamic Back-Stack Direction & Mid-Transition Gesture Safety**:
    - **Zero-Hierarchy Clean Architecture**: Avoids static depth numbers or hardcoded screen names.
    - Transitions evaluate returning vs advancing dynamically: when `targetState.id == navController.previousBackStackEntry?.id`, the transition is definitively a backward/pop operation and slides toward `End` (rightward). Otherwise, forward hierarchical transitions slide toward `Start` (leftward).
    - **Mid-Transition Back Swipe Collision Prevention (Fixes Issue #29)**: When a back gesture interrupts an in-flight forward entrance animation, Navigation Compose evaluates `enterTransition` / `exitTransition` instead of `popEnter` / `popExit`. Because the dynamic resolver checks `navController.previousBackStackEntry`, it accurately detects that the destination is the returning parent and enforces `SlideDirection.End`. This ensures the outgoing screen smoothly slides out to the right (→) and parent screen slides in from the left (→) without collision (`→ ←`) or blackout artifacts.
  - **Centralized Surface Background**: The full-screen background is managed centrally by `TidiloBackground` beneath the root `Scaffold` (`containerColor = Color.Transparent`) in `MainActivity.kt`. Because the lateral transitions maintain strictly adjacent (side-by-side) bounds with zero spatial overlap, individual screen roots avoid duplicate `.background()` modifiers, eliminating GPU overdraw while guaranteeing 100% seamless edge-to-edge visuals.
- **In-Page Content & Filter Transitions (`LibraryScreen.kt`)**:
  - Filter mode switching and Domain Grid ⇄ Domain DrillDown utilize a lightweight crossfade (`fadeIn(220ms)` togetherWith `fadeOut(180ms)`).
  - Eliminates jarring lateral page jumps during filter changes, giving users a responsive in-place refresh / redraw feel.

---

## 5. Edge-to-Edge & System Insets

Tidilo implements the Android 15+ official Edge-to-Edge guidelines:

1. **Full-screen Surface**: `MainActivity` calls `enableEdgeToEdge()` and sets `contentWindowInsets = WindowInsets(0, 0, 0, 0)` on the root `Scaffold`.
2. **Scroll Immersion & Actual Scroll Tracking**:
   - Lazy lists occupy the full physical screen with shared scaffold `contentPadding`: normally `statusBars + 104dp` at the top. Library reserves clearance for its fixed Search action; Search and other child pages have no bottom navigation bar. Secondary pages preserve their own navigation/IME and floating-action clearance.
   - Content flows freely under the translucent status and navigation bars.
   - **`onPostScroll` Consumption-based Connection**: Controls only hide when content **actually consumes vertical scroll** (`consumed.y < -30f`), completely preventing false dismissals on screens with few or non-scrollable items (such as empty Library). Reappears when content actually scrolls up (`consumed.y > +20f`) or is pulled down at the top overscroll boundary (`available.y > +20f`).
3. **Status Bar Scrim**:
   - When bars are hidden during scroll, a dynamic 4-stop vertical gradient scrim renders over the status bar:
     `listOf(scrimBg.copy(0.85f), scrimBg.copy(0.50f), scrimBg.copy(0.15f), Color.Transparent)`.
   - Protects white status bar icons (battery, clock, Wi-Fi) from clashing with bright scroll content while harmonizing with the theme background.
4. **IME Keyboard Insets**:
   - In `BookmarkDetailScreen`, keyboard handling binds directly to `WindowInsets.navigationBars.union(WindowInsets.ime)` with zero artificial spacers, ensuring seamless dismissal without lag or pop-in.

---

## 6. Reusable Component Specifications

### 6.1 Library Modal Navigation Rail

Library is the only root destination. Its top-left menu opens `ModalWideNavigationRail` with `hideOnCollapse = true`; no collapsed icon column occupies the Library. Search, Notification and Settings are secondary pages with Back navigation. Manual Import opens the URL-entry dialog.

The rail header fixes the application name and `ic_notifications` action above its scroll content. Search precedes the Filters section, followed by Newest first, Domain, RSS and Collection. Manual Import and Settings are bottom-aligned within that same scroll region, not pinned outside it. Each actionable row has an icon and label. See [Library Navigation](library_navigation.md) for actions, timestamp formatting and error recovery behavior.

Library retains its title/subtitle, exposes only the existing dashboard/gear display-mode selector on the right, and has a fixed compact Search action at bottom-right. Search retains its scrolling input, history, tag selector and feedback layout; it automatically focuses the search input and requests the keyboard on entry from Library or return from Tag Selection, and has no separate floating action or root tab.

### 6.2 `BookmarkItemCard`
- **Shape**: `RoundedCornerShape(24.dp)`.
- **Shared Bookmark Lighting**: `BookmarkCardStyle.kt` decorates list and grid bookmark cards (including Search) with a 1dp accent rim and a 4dp shadow. Grid cards retain their 16dp corners, image treatment, and long-press summary behavior.
- **Destination Accent**: Domain/RSS drill-down content provides its background beam's `domainColor` through `LocalCardAccentColor`, scoped inside each animated destination so outgoing cards retain their own accent. Unspecified accents use `MaterialTheme.colorScheme.primary`; existing domain image placeholders keep their own domain colors.
- **Incident Direction**: The rim and light surface wash use a pure accent fade from top-right (`Offset(Float.POSITIVE_INFINITY, 0f)`) to bottom-left (`Offset(0f, Float.POSITIVE_INFINITY)`), without neutral outline blending.
- **Light Appearance**: White containers with zero tonal elevation, rim alpha 0.38 to 0.04, a subtle inner wash from 0.045 to zero, brand ambient shadow alpha 0.25, and black contact/spot shadow alpha 0.10. Explicit white/black and colored shadows are intentional exceptions to general semantic-color guidance for this lighting treatment.
- **Dark Appearance**: Preserve list `surfaceContainerHigh` and grid `surfaceContainer`, with rim alpha 0.65 to 0.05, brand ambient shadow alpha 0.25, brand spot shadow alpha 0.50, and no inner wash. Appearance follows the applied color scheme's background luminance, including explicit theme overrides.
- **Drawing Order**: Draw shadows outside rounded clipping, wash behind content, and rim over content so images cannot conceal the illuminated edge.
- **Navigation Debounce Guard**: Incorporates single-click throttle protection (800ms debounce interval + `Lifecycle.State.RESUMED` navigation gate in `TidiloNavGraph`) preventing duplicate detail screens on rapid consecutive taps.
- **Engagement**: List and grid cards expose a 20dp Like icon inside a 48dp action target. Like toggles immediately without navigating to detail.
- **Hierarchy**:
  1. Title (with keyword highlighting when searching) + Like button.
  2. URL (1 line, ellipsis).
  3. Description (AI-synthesized one- or two-sentence summary).
  4. Tag row: `[Category] > [Subcategory]`.
  5. Entity tags: FlowRow of `CompactTagBadge` chips with `#` icon.

### 6.3 `CompactTagBadge`
- **Height**: Fixed `26.dp` container height (`Modifier.height(26.dp)`) with vertical center alignment, ensuring exact size uniformity across all tags (e.g. NSFW badge, ASCII entity tags).
- **Shape**: `RoundedCornerShape(12.dp)`.
- **Layout**: 14dp Vector icon + 6dp spacer + label text (single line) with 10dp horizontal padding.
- **Hit Highlighting**: When matching active search queries, container and content transition to high-contrast tonal colors.

### 6.4 `BookmarkDetailScreen` & Material 3 Toolbar
- **Layout Hierarchy**:
  - Root `TidiloScreenScaffold`: Hosts the full-screen scrollable body, fixed **Floating Header Actions** overlay (`DetailFloatingHeaderOverlay`), and bottom-anchored toolbar.
  - **Scroll Separation**: Both the top Floating Header Actions and the bottom toolbar are placed outside the scrollable `Column` so they remain fixed during vertical scrolling.
  - **Edge-to-Edge Status Bar Protection Scrim & Floating Top Actions**:
    - `BookmarkDetailScreen` omits the full-width "Details" text header bar across loaded, loading, error, and not-found states.
    - A permanent top status-bar protection gradient scrim (`statusBars` height + `8.dp` soft edge) blends from `topEdgeColor` (or theme `canvasColor` when no `og:image` is present) to transparent without intruding into the `og:image` body.
    - Two persistent `48.dp` circular floating buttons sit at the top-left (**Back** arrow) and top-right (**Save** with a floppy-disk icon `R.drawable.ic_save`), backed by `TidiloSurface(..., blurBackground = true)` (`SurfaceRole.FLOATING_BAR` / `SurfaceRole.PRIMARY_ACTION`). The floppy Save button is always visible when a bookmark is loaded, uses disabled alpha (`0.38f`) when `!hasChanges`, switches to the active `PRIMARY_ACTION` tone when `hasChanges` is true, and displays a `CircularProgressIndicator` while `isSaving` is true.
- **Seamless Hero Banner (`og:image` Mode & `DomainWatermark` Fallback)**:
  - **Full-Bleed `og:image` Placement**: Spans the full viewport width (`fillMaxWidth()`, `0.dp` horizontal margin) directly below the status bar top band. A full-width `BoxWithConstraints` supplies the available width; the image height is `min(maxWidth / calculateOgAspectRatio(bitmap.width, bitmap.height), 300.dp)` (`1.91:1` standard aspect ratio preserved edge-to-edge, with a `300.dp` height ceiling for tall portrait images). Only the floating Back and Save buttons sit over the artwork; no 1dp border rim or rounded corner clipping is applied to the hero banner.
  - **Dynamic Edge-Color Palette (`DetailHeroPalette`)**: `BookmarkDetailLighting.kt` and `OgImageManager` sample representative colors from the top ~15% band (`topEdgeColor`) and bottom ~15% band (`bottomEdgeColor`) alongside the dominant `accentColor` on `Dispatchers.Default`, calibrated via `calibrateDetailEdgeColor` for status bar and text legibility and animated with a 350ms `EaseOutCubic` transition. `OgImageManager` caches up to 128 palettes keyed by image source and bitmap generation and invalidates them on eviction or cache clearing.
  - **Minimal Edge Feathering & Symmetric Upward/Downward Gradient Extensions**: Over the `og:image` itself, top and bottom feathering is kept minimal (`12.dp` top / `14.dp` bottom) so artwork remains unobstructed while eliminating hard 1px seams. Above the image, the status bar band extends `topEdgeColor` upward and fades into transparent (`Transparent` -> `topEdgeColor.copy(alpha = 0.50f)` -> `topEdgeColor`). Below the image, a downward gradient extension (`bottomEdgeColor` -> `bottomEdgeColor.copy(alpha = 0.35f)` -> `Transparent`) hosts the above-title metadata and Bookmark Title.
  - **Measured Light Source**: The ambient radial bloom has radius `screenWidth * 1.05` and follows the banner's actual root-space center through scrolling, accompanied by a downward linear wash (`detailAmbientLight`).
  - **Reactive Cache & On-Demand Loading**: Integrated with `rememberOgImage` (`LocalCacheVisualSource` cache observer and exact-image update notifications, triggering background on-demand fetching when uncached), unified across `BookmarkDetailScreen` and `BookmarkGridCard`.
  - **Domain Icon Watermark Fallback (`DomainWatermark`)**:
    - When `og:image` is available, the Seamless Hero Banner is rendered and the background `DomainWatermark` is suppressed.
    - When `og:image` is absent (`ogImageUrl == null` or load fails) and image resolution has completed (`ogState.resolved`), top clearance (`statusBars + 72.dp`) is applied so the floating Back and Save buttons never overlap text, and `DomainWatermark` (`195dp`, 50% saturation, `0.14f` alpha, `RoundedCornerShape(20.dp)`) renders at `Alignment.TopEnd` with domain shoulder ambient lighting.
- **Above-Title Metadata & Classification Breadcrumb**:
  - Inside the downward gradient extension (padded by `20.dp` start/end) directly above the Bookmark Title:
    1. **Source Attribution Row**: Displays `Domain • Relative Time` (`DomainPolicyManager.getDisplayName(domain) ?: domain` and `TimeFormatter.formatRelativeTime`) in `MaterialTheme.typography.labelMedium` (`12.sp`, `onSurfaceVariant`). No favicon and no browser-jump icon are rendered here.
    2. **Classification Breadcrumb**: Immediately below the Source Attribution Row in the exact same `labelMedium` (`12.sp`) font size and `onSurfaceVariant` text color (plain text, no icons, no chip borders, no red highlight on NSFW), formatted via `formatClassificationBreadcrumb` as `Category > Subcategory - NSFW` (or subset). When Category, Subcategory, and NSFW are all empty/false, this line is completely omitted with zero vertical gap so the Bookmark Title pulls up directly beneath the Source Attribution Row.
    3. **Bookmark Title**: `MaterialTheme.typography.titleLarge`, `FontWeight.Bold`, `color = MaterialTheme.colorScheme.onBackground`.
- **Below-Title Entity-Only Tag Row**:
  - Placed directly beneath the header block without a `# Category & Tags` section title or classification chips.
  - Left (`weight(1f)`): `FlowRow` of `# Entity` chips (`BookmarkEntityChipsFlow` / `CompactTagBadge` in decorated themes, `AssistChip` in Material themes, wrapped in `CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified)` with `6.dp` horizontal and `8.dp` vertical spacing). When `editedTag.entities` is empty, renders a subtle interactive `+ Add tags` placeholder chip (`R.string.detail_add_tags_placeholder`) that opens `BookmarkTagEditDialog`.
  - Right: Compact `IconButton(Icons.Outlined.Edit)` (`contentDescription = stringResource(R.string.detail_edit_tags)`) that opens `BookmarkTagEditDialog`.
- **Editorial Summary Callout**:
  - The Summary section header uses a document/notes-style icon (`R.drawable.ic_summarize`) instead of an AI sparkle icon.
  - The Summary body renders inside an **Editorial Summary Callout** container featuring a `3.dp` start-edge vertical accent bar (`detailAccent` or `primary`) and a subtle horizontal gradient wash (`surfaceVariant` `0.42f` -> `0.16f`).
- **Bottom toolbar**: A rounded bar contains Trash, AI Summary, Collection membership star, and Like in that order. A separate rounded-square browser action of matching height sits to the right as the single canonical browser-launch trigger.
- **Integrated Category & Tag Editing Workflow (Parent Dialog + Screen-Level Bottom Sheet)**:
  - **Edit Action Trigger**: Positioned at the right end of the entity tag row (and on the `+ Add tags` placeholder when entities are empty).
  - **Parent Modification Dialog (`BookmarkTagEditDialog`)**:
    - Centralized `AlertDialog` managing Category (7 options), Subcategory (11 options), NSFW status toggle, and assigned Entities in one place.
    - **Category & Subcategory Dropdowns**: Single-select dropdown menus displaying localized strings.
    - **NSFW Content Toggle Section**: Positioned between Subcategory and Entity sections. Contains a Material 3 `Switch` allowing users to mark bookmarks as NSFW. When `AppConfig.isNsfwContentVisible` is OFF (NSFW hidden in Settings) and the toggle is ON, displays a contextual warning banner beneath the switch: `If you save while “ON” is selected, that bookmark will be hidden. (See “NSFW Contents” in Settings.)`.
    - **Entity Section Header**: Features borderless action icon buttons: `+` (Add new entity via `AddEntityDialog`) and `List Alt` (`R.drawable.ic_list_alt`, opens existing entity picker bottom sheet).
    - **Entity Preview Chips**: Rendered identically to Detail screen's chip style (`AssistChip`, 10dp rounded corners, tag icon) **without delete buttons** to maintain a clean layout.
  - **Screen-Level Bottom Sheet Anchor (`EntityTagPickerBottomSheet`)**:
    - Positioned at the root screen level (`BookmarkDetailScreen`) rather than inside the dialog's window tree.
    - Animates smoothly from the physical screen bottom, cleanly overlapping the lower half of the parent dialog.
    - Dismissal via the top-right OK button smoothly executes `sheetState.hide()` to slide down before invoking `onDismiss()`.
    - **Top Section**: Selected entity chips with dedicated delete ('x') buttons (`Icons.Default.Close`).
    - **Bottom Section**: All unique entity tags across the library sorted A-Z (`compareEntityNames`), functioning as toggle buttons with highlighted ON/OFF states.
    - Dismissing or dragging down cleanly commits changes to the parent dialog's draft state.
  - **Two-Stage Persistence & In-Memory Preview**:
    - Confirming the parent dialog updates `editedTag` and `editedIsNsfw` in memory, immediately previewing the changes in both the above-title Classification Breadcrumb and the below-title entity row, and activating the top-right floppy Save button.
    - Changes are atomically persisted to Room database and repository only when the user taps Save. Save validates that the edited bookmark still exists, applies all staged replacements across active bookmarks (including hidden content), and saves the current fields in one transaction. Trash is excluded. A missing target or any failure commits nothing; repeated taps cannot submit overlapping saves.
    - Manual variant entry compares skeleton identity against all active library tags, including hidden content, independently of Library filters. The lookup is collected only while manual entry or the entity picker is open, with lifecycle-aware cancellation. Closing both editors disposes the snapshot; reopening or retrying starts Loading and waits for a fresh snapshot. Detail viewing, note editing, and the parent tag dialog alone do not collect the library aggregate. Lookup loading or failure blocks confirmation; failure offers Retry.
    - A spelling conflict offers **Use existing tag** or **Replace across all bookmarks**, displays both spellings, and explains that replacement applies only after the detail screen Save. Dismissing the conflict changes nothing. Replacement choices remain in the parent editor until its confirmation; canceling that editor or discarding the screen never renames library tags.
    - The complete note, classification, entity, NSFW, replacement, and nested-dialog draft uses saved state for recreation. Automated restoration coverage remounts the SaveableStateRegistry; actual OS process death has not been exercised. Incoming Room updates preserve edits. Pending replacements alone enable Save, and failed Save retains all choices for retry. An explicit choice still applies if its last active occurrence disappears before Save; without an explicit replacement, a stale draft resolves the latest library spelling. Stored display spelling (including meaningful leading hashes) is rendered unchanged; the picker overlays pending spellings and shows each skeleton once.
- **Footer Full-URL & Timestamps**:
  - The full `bookmark.url` is rendered at the bottom of the scrollable body directly above the `Created: yyyy/MM/dd HH:mm` and `Updated: yyyy/MM/dd HH:mm` lines in the same right-aligned muted style (`MaterialTheme.typography.labelSmall`, `onSurfaceVariant.copy(alpha = 0.45f)`).
  - Bound via `combinedClickable` so tapping or long-pressing the footer URL performs haptic feedback (`HapticFeedbackType.LongPress`), copies the full URL to the clipboard, and displays the `detail_url_copied` toast. Browser launching is handled exclusively by the bottom toolbar's right-side square button.

---

## 7. Bottom-Right Action Placement

Library's Search action uses the shared PRIMARY_ACTION surface with `expanded = false`, a fixed 56dp container and 28dp Search icon, 24dp right/bottom content margins plus system navigation insets. Scrolling never resizes or shifts it. It is hidden during summary peek and absent from child pages. Other detail-page action menus retain their existing geometry and behavior.

### Expansion Rules for Sub-Menus (FAB Menu Items)
When a standalone 86dp bottom-right button expands into a menu, child menu pills must scale up in proportion:
- **Child Pill Container**: `CircleShape` with `horizontal = 24.dp, vertical = 16.dp` padding.
- **Child Icon**: Enlarged to **`28dp`**.
- **Child Typography**: Enlarged to **`titleMedium`** (16sp, SemiBold).
- **Vertical Alignment**: Right-aligned (`Alignment.End`) flush with the parent FAB right edge, with `14dp` vertical gap between items and `16dp` gap to the toggle FAB.

---

### 6.5 `DomainWatermark`
- **Dimensions & Effects**: Default `195dp` size, `ColorMatrix` with 50% saturation (`0.50f`), 0.14f alpha, clipped with `RoundedCornerShape(20.dp)`.
- **Shared Consistency**: Shared seamlessly between card feeds (`BookmarkCard`, `SearchScreen`), domain grid (`DomainCard`), and detail views (`BookmarkDetailScreen`).
- **Asynchronous & Non-Blocking Performance**: Uses `rememberDomainVisual` (`CacheVisualSource`) to resolve cached icons and recover missing icons alongside calibrated accent colors under a single subscription without blocking the UI. Imports and visible consumers share bounded per-domain requests. Completion updates bitmaps and accent colors without restarting active icon producers; cache generation changes recreate them. See [Domain Icon Recovery](domain_icon_cache.md).

### 6.6 Import notifications

Manual Import opens the URL-entry dialog from the Library rail. Notification lists saved history and import/RSS XML errors with creation timestamps. Error rows offer Retry and Delete through their overflow menu; there are no Import overview cards or swipe/Undo actions.

### 6.7 NSFW Contents Settings & Badge Specification
- **Settings Screen Menu Icons (`SettingsScreen.kt`)**:
  - All menu card icon containers use `primaryContainer` with icon tint `onPrimaryContainer`. Manual domain icon re-fetch is removed; cache clearing remains available and explains automatic preview/icon recovery.
- **NSFW Settings Screen (`NsfwSettingsScreen.kt`)**:
  - Clean top bar with `Icons.AutoMirrored.Filled.ArrowBack`.
  - Top options configured as 20dp rounded cards (`surfaceContainerHigh`):
    - Layout: Flattened `Row` structure (`Surface(Icon, 40.dp, primaryContainer/onPrimaryContainer)` + `Spacer(12.dp)` + `Column(Modifier.weight(1f))` + `Spacer(12.dp)` + `Switch`), with `padding(horizontal = 16.dp, vertical = 14.dp)` to maximize usable text width and eliminate mid-character line wraps before the Switch toggle.
    - **"Show NSFW Content" Switch**: Controls `AppConfig.isNsfwContentVisible`. Default ON.
    - **"Bookmark NSFW Content" Switch**: Controls `AppConfig.isNsfwContentBookmarkable`. Default ON.
  - **NSFW Domain Management Section**:
    - Lists all registered domains with a `Checkbox` on the left and domain name (with cached favicon/watermark icon) on the right.
    - Interactive row tap toggles domain NSFW classification in `DomainPolicyManager`.
- **Card & Detail NSFW Indicator**:
  - `BookmarkCard` displays a prominent red-toned `NSFW` badge (`errorContainer` background, `onErrorContainer` text) immediately adjacent to the `[category] > [subcategory]` chips when `bookmark.isNsfw == true`.
  - `BookmarkDetailScreen` displays `NSFW` inside the plain-text Classification Breadcrumb (`Category > Subcategory - NSFW` or `NSFW`) above the Bookmark Title in `labelMedium` (`12.sp`, `onSurfaceVariant`) without high-emphasis color or chip borders.

### 6.8 `DomainCard` & Library Domain Filter Mode
- **2-Column Grid Layout (`LazyVerticalGrid(GridCells.Fixed(2))`)**:
  - Horizontal/vertical arrangement with `12.dp` spacing, `20.dp` screen horizontal padding.
  - Content padding comes from the shared screen scaffold: the safe top inset plus the measured header row/fade/gap at the top, and the safe bottom inset plus `104dp` of Search-action clearance at the bottom.
- **Card Design (`DomainCard.kt`)**:
  - Layout: Top-right diagonal stacked deck structure with a fixed 2-layer background stack (`168.dp` total height, `154.dp` card height).
    - Deepest Layer (Layer 2): `surfaceContainerLow` container with `padding(start = 14.dp)`, `154.dp` height, top offset `0.dp`, peeking towards top-right (`x = TotalWidth - 7..TotalWidth`, `y = 0..7dp`), and subtle border (`dominantColor.copy(alpha = 0.18f)`).
    - Intermediate Layer (Layer 1): `surfaceContainer` container with `padding(start = 7.dp, end = 7.dp)`, `154.dp` height, top offset `7.dp`, peeking towards top-right, `1.dp` shadow elevation, and border (`dominantColor.copy(alpha = 0.28f)`).
    - Front Layer: Interactive `Card` with `padding(end = 14.dp)`, `154.dp` height, bottom-left aligned (`offset(y = 14.dp)`), `2.dp` elevation, `RoundedCornerShape(20.dp)`, and border (`dominantColor.copy(alpha = 0.42f)`).
  - Dark-mode color calibration:
    - Dominant colors are calibrated via `ensureDarkThemeLegibility` in `DomainIconManager`, boosting blue/violet hues (190° - 275°) to minimum brightness `0.92f` and moderate saturation `0.58f` (electric blue), and guaranteeing minimum value `0.72f` across all tones.
    - Ambient illumination: `Brush.linearGradient` blending calibrated brand color (`dominantColor.copy(alpha = 0.32f)` to transparent) on the front card.
  - Container color: `MaterialTheme.colorScheme.surfaceContainerHigh`.
  - `DomainWatermark`: Scaled to `size = 96.dp`, alpha `0.20f`, positioned at `Alignment.TopEnd` with `offset(x = 16.dp, y = (-6).dp)`.
  - Content layout: Vertical `Column` with `14.dp` padding inside front card:
    - Display Name header (shared by Domain and RSS decks): Bold, max 3 lines. Select typography by Unicode code point count: up to 10 uses `titleMedium` (16sp / 24sp); 11–18 uses `titleSmall` with 14.5sp / 19sp; 19 or more uses `bodyMedium` with 13.5sp / 17sp. Overrides are local to the collection title. Ellipsis remains the final fallback beyond three lines. Falls back to domain URL if empty.
    - Domain URL: `bodySmall`, high-contrast `onSurfaceVariant` color, single line with ellipsis.
    - Bookmark count badge: Pill surface badge (`dominantColor.copy(alpha = 0.20f)`) with bookmark icon (`Icons.Default.Bookmark`) and calibrated high-luminance count text (`"%d items"`).
- **Drill-down Interaction & Radiant Ambient Background**:
  - Tapping a domain card opens the domain-filtered bookmark list (rendered using standard `BookmarkItemCard` in latest-first order).
  - **Radiant Brand Background (2/3 Screen Spread)**:
    - Uses domain dominant color (`selectedDomainColor`), resolved asynchronously via `rememberDomainVisual` (`CacheVisualSource` cached dominant color or fallback generated accent color).
    - Radiant beam (`Brush.linearGradient`): 6-stop gradient radiating from top-right (`Offset(size.width * 1.05f, -size.height * 0.02f)`) towards bottom-left (`Offset(-size.width * 0.15f, size.height * 0.90f)`), holding visible color across ~2/3 of the screen before gently tapering (`0.32f` -> `0.22f` -> `0.13f` -> `0.05f` -> `0.015f` -> `Transparent`).
    - Corner radial glow (`Brush.radialGradient`): Centered at `Offset(size.width, 0f)` with deep radius `size.height * 0.72f` (~650-700dp), bathing the upper 2/3 of the screen in a soft atmospheric bloom (`0.22f` -> `0.13f` -> `0.05f` -> `0.012f` -> `Transparent`).
    - Seamlessly harmonizes with the card top-right ambient illumination, establishing deep spatial coherence.
  - Screen header shows domain display name with a back navigation arrow button (`Icons.AutoMirrored.Filled.ArrowBack`).
  - Domain and RSS drill-down titles remain single-line and scale by Unicode code point count: up to 6 uses `headlineMedium` (28sp / 36sp); 7–9 uses `headlineSmall` with 23sp / 28sp; 10–13 uses `titleLarge` with 20sp / 25sp; 14 or more uses `titleMedium` with 17sp / 22sp. The title column takes the remaining width between navigation and action buttons. `basicMarquee(initialDelayMillis = 2000)` scrolls overflowing titles without ellipsis; fitting titles remain still. Normal collection headers retain their existing typography. Title text and drill-down status travel together through `AnimatedContent`, preserving outgoing typography during navigation.
  - Header subtitle text color dynamically coordinates with `selectedDomainColor`.
  - System back gesture is captured via `BackHandler` to return to the domain grid rather than exiting or switching tabs.
  - **Drill-down Content State Retention during Exit Transition**: Each animated destination collects a bounded Pager for its own domain or feed identity. The ViewModel retains the selected session across detail navigation to preserve its loaded window and scroll position. Animated compositions lease their sessions; nonselected sessions cancel when the last exit finishes. Rapid A-to-B-to-A navigation reactivates A even if its composition is reused. Clearing the global selection cannot replace outgoing items with another query or an empty feed-grid generation. NSFW visibility changes discard visible destinations immediately and use a new visibility-specific session.
- **Page Transition Animations (`AnimatedContent` & `AnimatedVisibility`)**:
  - **Forward and Backward Transitions**: Domain and RSS grid/drill-down navigation uses a symmetric crossfade (220ms in, 180ms out, EaseInOutCubic). Loading, error, and empty content render inside the persistent transition host.
  - **Filter Mode Transition**: Uses the same crossfade between Newest First, Domain, and RSS destinations.
  - **Header Elements**: Library uses one persistent 48dp leading navigation button with 4dp trailing padding: Menu at overview level, Up inside Domain, RSS and Collection groups. Its decorative glyphs crossfade symmetrically over 220ms with EaseInOutCubic; button bounds and title position remain fixed. The current hierarchy owns the action and sole accessible label throughout the transition. Initial composition has no entrance transition, and disabled system animations settle immediately. Header title and subtitle crossfade via `AnimatedContent`. Subtitle text and accent are one immutable presentation, so outgoing bookmark counts retain their color throughout fade-out. Counts are observed for the destination identity; known collection counts seed drill-down headers without an intermediate Loading label.
  - **Scroll Position Memory**: Grid scroll state (`domainGridState`) is preserved when popping back from a drill-down list, while the drill-down list resets to top (`scrollToItem(0)`) upon selection.

### 6.9 `FrostedHeaderContainer`
- **Purpose**: Eliminates the harsh, hard-edged cutoff line between the fixed/sliding header and scrolling list content in Edge-to-Edge screens.
- **Stationary Canvas and Foreground Chrome**: `TidiloBackground` draws the central canvas. Screen chrome selects that canvas and its own body source; its output remains outside capture. Header blur, fallback and tint are composited in one bounded background layer and masked once, keeping foreground content sharp and the fade continuous.
- **14-Stop Smootherstep Attenuation Curve**:
  - Implements Ken Perlin's quintic smootherstep equation ($S(t) = 6t^5 - 15t^4 + 10t^3$, $\alpha(t) = 1 - S(t)$) sampled across 14 finely spaced gradient stops.
  - Because both first and second derivatives are zero at $t=0$ and $t=1$ ($d\alpha/dt = 0$, $d^2\alpha/dt^2 = 0$), the transition is optically seamless, entirely eliminating optical Mach banding.
- **Shared Geometry (`decayOverlap = 6.dp`, `fadeHeight = 18.dp`)**:
  - The unchanged smootherstep drawing starts decay 6dp before the content row ends and reaches transparency after its 18dp trailing spacer. Decay overlap and body clearance are distinct tokens.
  - `HeaderDefaults` defines an 80dp standard content row, excluding the status bar and including internal vertical space. `TidiloFrostedHeader` centers the title/subtitle block and navigation/actions inside that row. No extra outer 4dp top/bottom allowance is added.
  - The fade ends at `statusBarTop + rowHeight + 18dp`. Resting content begins another 6dp below it: normally `statusBarTop + 104dp`. Header and body use the same font-aware row height, preventing independent magic-number drift.
  - Enlarged fonts may increase the shared row height beyond 80dp, using baseline headlineMedium/labelMedium measurements plus 8dp. Actual title length, local title scaling, subtitle absence and transition state never change the row height within the same typography configuration.
  - Status bar and title text retain 100% legibility contrast, while the bottom shelf dissolves organically into the underlying content.
- **Pure Neutral Translucency (No Color Overlays on Header)**:
  - Header relies exclusively on neutral frost translucency (`contentAlpha = 0.85f`) and smootherstep dissolution without applying artificial colored lighting layers, eliminating color collision and shelf artifacts.
  - The underlying screen's ambient illumination shines naturally through the translucent frosted glass with complete optical clarity.
- **Touch-through Transparency**: Lower dissolve zone uses a non-interactive `Spacer`, allowing pointer events to interact freely with underlying lazy lists.
- **Header Body Input**: The content `Box`, including status-bar padding, consumes taps with `detectTapGestures` so blank space and inactive titles cannot activate cards beneath the frost. The dissolve spacer remains outside this input boundary. The container itself has no clickable accessibility semantics.
- **Header-Origin Scrolling**: Library, Search and bookmark detail supply their existing active `ScrollableState`. A vertical `scrollable` on the header body forwards drags and flings to that state, because consuming overlay taps alone removes the underlying list from the hit path. Header scroll progress is tracked separately from body animation progress while sharing the body's scroll mutation arbitration; an ongoing body animation must not steal title or icon taps as immediate drags.
- **Adopted Screens**: Library, Search, Notification, Settings and its subpages, bookmark details, tag selection and AI review. Their headers remain independent of scrolling content, including loading/error/not-found states on detail/review pages.

### 6.10 Application-Wide Title Bar & Header Geometry Standardization
- **Shared Source of Truth (`HeaderDefaults`, `TidiloFrostedHeader`, `TidiloScreenScaffold`)**:
  - Settings, NSFW, RSS, Storage, Debug and AI generation settings use `TidiloPageHeader` for the title/navigation boilerplate. Its optional subtitle slot retains Debug's secondary text, and its actions slot retains the generation reset control.
  - `TidiloFrostedHeader` and `TidiloPageHeader` expose an optional `onTitleClick` callback. Only the weighted title/subtitle column is clickable when supplied, with `TextHandleMove` haptic feedback and no ripple. Navigation and action slots remain independent; blank row/status-bar padding only absorbs taps.
  - Library binds the title action to `animateScrollToItem(0)` on the active Newest list/grid, Domain deck/detail list/grid, or RSS deck/detail list/grid. Index 0 with offset 0 skips animation. Other states retain their positions under existing navigation rules, and changing the active state cancels any pending title-scroll coroutine. Search and bookmark detail enable header-origin scrolling without adding a title-tap action.
  - The header owns status-bar padding; the body receives matching top clearance from the scaffold. Do not apply status-bar padding again to the body.
  - Horizontal Screen Margin: `start = 20.dp, end = 20.dp`.
  - Title Text Typography: `MaterialTheme.typography.headlineMedium`, `fontWeight = FontWeight.Bold`, `color = MaterialTheme.colorScheme.onBackground`.
  - Action/navigation centers share `statusBarTop + rowHeight / 2`; device-specific pixel coordinates are not geometry tokens.
- **Back Navigation Affordance (`IconButton`)**:
  - Dimensions & End Spacing: 48dp interactive slot with 4dp trailing separation; icon artwork is unchanged. Animated navigation slots reserve no width after exit.
  - Icon Vector & Tint: `Icons.AutoMirrored.Filled.ArrowBack` with `tint = MaterialTheme.colorScheme.onBackground`.
  - Horizontal Title Text Alignment: 72dp (`20dp + 48dp + 4dp`) from the start edge when the navigation slot is present, and 20dp when absent.
- **Secondary Actions & Toolbar Buttons**:
  - Header Icon Buttons: 48dp interactive slots (Library menu and display mode, Notification overflow actions, Reset Wrench in GenerateContentRequest). Title slots take the remaining width so actions stay visible at large font sizes.
- **Unified Screens**:
  - Library has a menu button. Search and Notification use the shared Back header; the Import overview is removed. Search automatically focuses the search input and shows the software keyboard when entered from Library or returning from Tag Selection, while returning from Bookmark Detail preserves the scroll position without re-focusing.
  - Sub-Screens with Back Button: Search, Notification, `BookmarkDetailScreen`, `TagSelectionScreen`, `SettingsScreen`, `NsfwSettingsScreen`, `SettingsDebugScreen`, `GenerateContentRequestScreen`, `StorageScreen`, `RssSettingsScreen`, `BookmarkAiReviewScreen`. Library Domain/RSS drill-downs also show Back; Library's root uses its menu action.
- **Visibility Policy**:
  - `hideHeaderOnScroll` defaults to false. Library and Search explicitly enable it and reuse MainActivity's `LocalBarsVisible` signal. Notification, Settings and the other secondary pages keep fixed headers.
  - One shared slide/fade implementation retains the existing spring and 200ms enter/150ms exit fades. Peek suppression is a separate `headerVisible` input. Header disappearance never collapses body padding or jumps the list.
  - The scroll-related `LocalBarsVisible` read lives inside the narrow `ScaffoldHeaderVisibility` child, independently of the body and overlay composition scopes. Fixed headers do not subscribe to that scroll signal.
  - Content is drawn first, the header second, and optional overlays last. The app shell owns the Library-only modal rail and compact Search action; scroll detection remains shared. Search reserves only system navigation insets and regular safe spacing, with IME padding applied to its body. Library title/subtitle/list-grid transitions remain unchanged.

### 6.11 Card and Detail Layout Rhythm
- **Bookmark Card (`BookmarkCard.kt`)**:
  - Description text renders directly using `MaterialTheme.typography.bodyMedium` with keyword highlighting when search terms are present.
- **Bookmark Detail Screen Spacing & Layout Rhythm (`BookmarkDetailScreen.kt`)**:
  - Above-title header metadata (`Source Attribution Row` and optional `Classification Breadcrumb`) sits inside the seamless downward gradient extension directly above the Bookmark Title. When `Classification Breadcrumb` is `null` (no category, subcategory, or NSFW), zero vertical spacer is added so the title pulls up directly beneath `Domain • Relative Time`.
  - Below the title, the Entity-only tag row (`BookmarkEntityChipsFlow` under `AppTheme.MODERN` or `AssistChip` under `AppTheme.DEFAULT`, or the `+ Add tags` placeholder when entities are empty) and its right-aligned `Edit` `IconButton` are scoped under `CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified)` to eliminate invisible accessibility touch-target inflation.
  - Standard vertical section spacing between the Entity tag row, Editorial Summary Callout, and Note section is `24.dp`, with `10.dp` between section headers and their content containers.

### 6.12 Tag Edit Dialog Dropdown Menu Specification (`BookmarkTagEditDialog.kt`)
- **Full-Width Popup Anchoring**:
  - The Category and Subcategory selection fields wrap their `Surface` and `DropdownMenu` inside `BoxWithConstraints(modifier = Modifier.fillMaxWidth())`.
  - The `DropdownMenu` applies `Modifier.width(maxWidth)`, ensuring the dropdown menu spans the exact width of the parent selector field rather than clipping or floating arbitrarily to the left/right.
- **Bounded Menu Height & Smooth Scrolling**:
  - Menu height is constrained using `Modifier.heightIn(max = 250.dp)` (roughly 5 visible items).
  - Leveraging Compose's built-in `DropdownMenuContent` vertical scroll support, longer lists (such as Subcategory with 11 items) scroll smoothly without extending off-screen or obscuring the dialog layout.
- **Visual Contrast & Boundary Definition**:
  - Since `AlertDialog` sits on `surfaceContainerHigh`, dropdown container color is elevated to `MaterialTheme.colorScheme.surfaceContainerHighest`.
  - Harmonizes with the Detail screen's Entity tag chips (`AssistChip`) by applying an identical 1dp border (`MaterialTheme.colorScheme.outlineVariant`) across both the selector field (`Surface`, `RoundedCornerShape(12.dp)`) and popup container (`DropdownMenu`, `RoundedCornerShape(16.dp)`).
  - Similarly, all Entity chips in `BookmarkTagEditDialog` and `EntityTagPickerBottomSheet` apply `BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)`, establishing unified border geometry and color hierarchy.
- **Entity Chip Color & Typography Synchronization (`EntityTagPickerBottomSheet.kt`)**:
  - `SelectedEntityChip` background is unified with active `AllEntityToggleChip` to pure primary color (`MaterialTheme.colorScheme.primary`) with `onPrimary` content color and `FontWeight.Bold`, resolving the previous mismatch where selected chips appeared darker in `primaryContainer`.
- **Scroll Affordance & Vertical Scrollbar Indicator**:
  - Solves the "False Floor" (invisible scroll continuation) issue using a custom dynamic vertical scrollbar (`Modifier.verticalScrollbar`).
  - Passes dedicated `ScrollState` (`categoryScrollState` / `subcategoryScrollState`) to `DropdownMenu(scrollState = ...)`.
  - Only renders when `scrollState.maxValue > 0` (non-scrollable lists remain clean with no extra chrome).
  - Displays a subtle 4dp primary track (`primary.copy(alpha = 0.15f)`) and dynamic primary thumb (`primary`) on the right edge (`paddingEnd = 6.dp, paddingVertical = 12.dp`), providing immediate visual cues on scrollability and current scroll position.
- **Leading Checkmark Alignment**:
  - Moved checkmark icon from `trailingIcon` to `leadingIcon` (display order: `[Checkmark] [Item name]`).
  - Selected items render `Icons.Default.Check` (`size = 18.dp`, tinted with `primary`).
  - Unselected items render an empty `Spacer(modifier = Modifier.size(18.dp))` in `leadingIcon`, maintaining uniform vertical text alignment across all menu entries.

### 6.13 Entity Tag Maximum Limit (10) & Error Feedback Specification (`BookmarkTagEditDialog.kt`, `EntityTagPickerBottomSheet.kt`)
- **Global Entity Limit Constant (`Tag.MAX_ENTITIES = 10`)**:
  - Each bookmark supports a maximum ceiling of 10 assigned Entity tags.
- **Header Selection Count Affordance**:
  - Both `BookmarkTagEditDialog` and `EntityTagPickerBottomSheet` dynamically display current selection and limit as `(${selectedEntities.size}/${Tag.MAX_ENTITIES})` in their Entity section headers.
- **Parent Dialog Add Button Guard & Inline Error Feedback (`BookmarkTagEditDialog.kt`)**:
  - Tapping the `+` button when 10 entities are already selected prevents `AddEntityDialog` from opening.
  - Triggers an inline error warning banner (`Surface` with `errorContainer.copy(alpha = 0.35f)`, error `Info` icon, and `detail_tag_entity_limit_inline`) rendered above the entity preview chips until an entity is removed or the dialog closes. No intrusive dialog is displayed since the inline error banner clearly indicates the limit.
- **Bottom Sheet Picker Maximum Limit Affordance (`EntityTagPickerBottomSheet.kt`)**:
  - When 10 entities are already selected, unselected entity chips in the bottom library section are visually dimmed (`alpha = 0.38f` text and outline, `alpha = 0.25f` background) to communicate non-addable state.
  - Tapping an unselected chip while at maximum limit blocks addition and immediately displays a contextual inline warning banner (`detail_tag_entity_limit_inline`) above the chip toggle grid.
  - Selected chips remain fully active and removable; deleting any chip immediately clears the warning banner and restores full visual contrast to unselected chips.

### 6.14 Library Bookmark List View Mode Dropdown Menu Specification (`LibraryScreen.kt`, `LibraryViewMode.kt`)
- **Header Action Button & Material Symbols Icon**:
  - The only right-side action in `LibraryScreen.kt`'s frosted header row.
  - Sized at `48.dp` matching the Settings `IconButton` interactive slot.
  - Displays `R.drawable.ic_dashboard_2_gear` (24dp); the popup is anchored to this button without a legacy two-button offset.
- **Dropdown Menu Positioning & Container Geometry**:
  - Anchored directly to the button container using Material 3 `DropdownMenu`.
  - Width is fixed to `200.dp`, providing a generous and polished layout that avoids feeling narrow or cramped.
  - Uses the single display-mode button as its anchor, without an explicit offset for former action slots.
  - Follows Tidilo menu design language with `shape = RoundedCornerShape(16.dp)`, `containerColor = MaterialTheme.colorScheme.surfaceContainerHigh`, and `tonalElevation = 6.dp`.
  - Wrapped with `CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides localConfig)` to ensure accurate localization in dynamic popup windows.
- **Item Order & Leading Checkmark Alignment**:
  - Conforms to the `[Checkmark] [Icon] [Display Name]` sequence.
  - Selected items render `Icons.Default.Check` (`18.dp`, `primary`), icon in `primary` (20dp), and text label in `FontWeight.Bold` and `MaterialTheme.colorScheme.primary`.
  - Unselected items render an empty `Spacer(modifier = Modifier.size(18.dp))`, icon tinted with `onSurfaceVariant` (`20.dp`), and label in `FontWeight.Normal` and `MaterialTheme.colorScheme.onSurface`.
  - Maintains strict vertical column alignment across all menu entries without shifting text or icons.
- **Accessibility (A11y)**:
  - Each `DropdownMenuItem` declares `Modifier.semantics { selected = isSelected }`, ensuring screen readers and accessibility services (TalkBack) accurately announce the active mode.
- **Menu Items (`LibraryViewMode`)**:
  - `LIST`: "List" (`R.string.library_view_mode_list`), icon `R.drawable.ic_list` (Material Symbols Outlined `list`).
  - `GRID`: "Grid" (`R.string.library_view_mode_grid`), icon `R.drawable.ic_grid_view` (Material Symbols Outlined `grid_view`).
- **State & Scope Boundary**:
  - Selected view mode is tracked and persisted via `LibraryViewModel.viewMode` (saved in `tidilo_library_prefs`).
  - Tapping an item updates selection state and dismisses the menu, smoothly transitioning the bookmark list layout via AnimatedContent crossfade.

### 6.15 Bookmark Grid View & Card Specification (`BookmarkGridCard.kt`, `TimeFormatter.kt`)
- **2-Column Grid Geometry & Spacing**:
  - Rendered using `LazyVerticalGrid` with `columns = GridCells.Fixed(2)`.
  - Outer screen edge horizontal padding: `20.dp` (matching frosted header and existing list cards).
  - Inter-column gap (`horizontalArrangement`): `8.dp` (tightened spacing maximizing card and image surface area).
  - Vertical row spacing (`verticalArrangement`): `10.dp`.
- **Card Container & Shape (`BookmarkGridCard.kt`)**:
  - `Surface` container with `shape = RoundedCornerShape(16.dp)` and `tonalElevation = 0.dp`. Uses white in Light Mode and `surfaceContainer` in Dark Mode, with the shared bookmark lighting defined in section 6.2.
  - Supports combined touch interactions via `combinedClickable(onClick, onLongClick)`.
  - Fully uniform vertical height across equal-width cards: fixed image aspect ratio `16f / 9f` combined with fixed-height information container (`132.dp`), shared by Library and Search grids.
- **Top Image Region (16:9 Aspect Ratio)**:
  - Fixed ratio `16f / 9f` preserves the framing of 16:9 artwork with `ContentScale.Crop`, clipped to card top corners (`topStart = 16.dp, topEnd = 16.dp`). Other image ratios are still cropped to fill.
  - When the bookmark's exact og:image is cached: Decodes WebP from local cache storage and displays using `ContentScale.Crop`.
  - When og:image is absent or pending: Displays the domain accent gradient with a centered cached domain favicon (`36.dp`), then a bundled Material Rounded Link icon (`24.dp` inside a `36.dp` rounded container) if no icon exists. Uses the theme primary color when the domain is unavailable. Never uses initials or another bookmark's OG image.
  - Missing/corrupt previews with a persisted OG URL recover on demand through shared, four-worker-limited background requests. Exact-image completion updates cards without restarting sibling downloads. See [Bookmark Preview Cache](og_image_cache.md) for cancellation, retry, and cache-clear semantics.
  - Domain icon network recovery waits for a completed OG miss, or starts immediately when no OG URL exists. Cached icons may display during OG loading. Successful OG previews do not trigger icon network recovery from that card.
- **Bottom Information Hierarchy (Fixed Height: 132dp)**:
  - Internal padding: `start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp`, keeping the date above the rounded bottom corners. Fixed height does not guarantee unclipped text at arbitrary system font scales.
  - ❶ **Domain Name**: Subtle caption (`labelSmall`, `11.sp`, `lineHeight = 15.sp`, `color = MaterialTheme.colorScheme.outline`, single-line ellipsis).
  - ❷ **Bookmark Title**: Positioned immediately below the domain name. High-readability 3-line reservation (`minLines = 3, maxLines = 3`, `style = MaterialTheme.typography.titleSmall`, `FontWeight.SemiBold`, `overflow = TextOverflow.Ellipsis`).
  - ❸ **Created Date (Relative Time)**: Single-line bottom caption (`labelSmall`, `outline`, formatted via `TimeFormatter`), on its own row below the title. Snapped to the bottom-left of every card tile via `Spacer(Modifier.weight(1f))` ensuring a perfectly aligned baseline across all cards regardless of title line count.
- **Relative Time Formatting Rules (`TimeFormatter.kt`)**:
  - `< 1 minute`: "Just now"
  - `1 to 59 minutes`: "1 minute ago" / "%d minutes ago"
  - `1 to 23 hours`: "1 hour ago" / "%d hours ago"
  - `1 to 29 days`: "1 day ago" / "%d days ago"
  - `>= 30 days`: "yyyy/MM/dd" (e.g. `2026/09/20`)
- **Prominent Borderless Summary Peek Overlay (`LibraryScreen.kt`)**:
  - Long-pressing any grid card automatically transitions the screen into full Edge-to-Edge display (hiding the header) and reveals a clean, borderless overlay.
  - Background blur is completely omitted for crisp, natural clarity; the overlay applies the theme background color at 85% opacity (`MaterialTheme.colorScheme.background.copy(alpha = 0.85f)`).
  - Displays the `✨ AI Summary` badge and summary text in `MaterialTheme.typography.bodyMedium` (lineHeight 24sp), positioned at the shared body top clearance, normally `statusBars + 104dp`.
  - Tapping anywhere on the screen or using the system back gesture dismisses the peek overlay and restores the previous Edge-to-Edge/bars state cleanly.
- **Shared View Mode Synchronization**:
  - The active `LibraryViewMode` (`LIST` or `GRID`) is uniformly applied to both the primary **Newest First** list and the **Domain > BookmarkList (Drill-Down)** list.
  - Layout transitions between `LIST` and `GRID` use `AnimatedContent` with smooth Crossfade (`fadeIn(220ms)` + `fadeOut(180ms)`).


### 6.16 Paged bookmark results

- Timeline, domain drill-down, and search render LazyPagingItems with stable bookmark-ID keys in list and two-column grid layouts.
- Initial load, refresh error, and true empty results are distinct states. Initial loading never displays an empty-library message. Domain grids also wait for their first aggregate result.
- Prepend and append loading/error feedback supports retry after earlier or later pages have been evicted. An error takes precedence over another direction's spinner.
- Library domain/feed/mode changes create destination-owned Pager flows; outgoing flows remain only until their animations are disposed. Search retains its ViewModel-owned query generation. NSFW changes discard existing library destinations immediately. Separate remembered list/grid scroll states are retained. Domain positions reset only when the selected filter changes, including across detail return.
- Detail and AI-review screens remember a single-ID observation per ID/retry and distinguish loading, missing/deleted, and failed reads. Saving or trashing only navigates after the database operation succeeds; failures retain the screen and show a retryable action/message.
- Tag selection counts come from all matching database rows, independently of loaded pages. Settings domain lists use distinct-domain queries.


Paged UI review details: domain-card grids use domain aggregation state exclusively;
bookmark load/error feedback belongs only to timeline and domain bookmark results.
Search grid cards share the library summary peek overlay and dismiss it on tap,
back, or query change. A changed query resets both layout positions with a
non-suspending request; returning from details with the same query preserves them.


## Storage Dashboard

Settings > Data Management includes a Storage entry showing collection counts (Hero card) and physical cache storage in rounded tonal cards, with a proportional multi-color StorageBar and consolidated cache clearance. Sections expose independent loading/retry states. Storage scans run on entry and resume, with error retry affordances. See [Storage Dashboard](application_summary.md) for exact counting and formatting rules.

## Debug Web Parser

The Body Text panel offers Readability4J, Jsoup Clean, and Prompt modes. Readability4J retains the original extraction for comparison; Prompt shows only the full ad-filtered body before snippet truncation, without prompt instructions or metadata. All modes use Copy Body to copy exactly the displayed text. HTTP error bodies remain inspectable and copyable without invoking production preparation or AICore. The three filter chips wrap onto additional rows on narrow screens or with large fonts. Details use the full width with the copy button below them.

## Settings and AI Analysis

Settings groups existing rows into General (Language, Notification, AI Analysis), Data Management (Storage, RSS), and Debug cards. Cards use 20dp corners, surfaceContainerHigh, 20dp row padding, 40dp decorative circular icons, and inset outlineVariant dividers at alpha 0.25. Navigation rows expose a single Button target; text wraps within its weighted column.

General > AI Analysis opens a dedicated secondary page using the shared fixed header and centralized slide/predictive-back transitions. Its sections are Using AI Model, Automation, AICore error fallback, AICore parameters, and Content Safety. AICore and Disable are inline radio rows in a selectable group, each at least 48dp high. A fresh installation automatically selects AICore only when the initial readiness check returns AVAILABLE; otherwise it selects Disable. Existing/manual selections remain unchanged. Each page entry checks readiness again: AICore is unselectable and its label uses 0.38 alpha while checking or unavailable, with checking guidance or an error-colored unavailable explanation. Disable remains enabled. The check never writes the selected mode, downloads a model, generates content, or records inference usage.

Automation, fallback, and request parameters appear only for the AICore selection. Changing to Disable slides these sections upward by one-third of their height while fading and shrinking vertically with NoBouncy/MediumLow springs; enabling AICore reverses that motion. Hidden sections retain their saved preferences. Automation exposes one Switch row target. Fallback uses inline radio choices (Disable, If the quota is exceeded, All AICore errors), independent of the global model selection. Content Safety remains accessible in either mode and opens the existing NSFW Contents page. Long text and large fonts grow rows vertically; navigation-bar clearance keeps the final row reachable.

## RSS Settings and Library Scope

In RSS feed add/edit dialogs, both mode radio choices remain enabled when global AI Analysis is Disable. A red warning message (`MaterialTheme.colorScheme.error`) directly below the radio group explains that feeds are imported in Direct mode automatically; it appears for either selected mode and disappears when AICore is enabled. Saving preserves the selected feed mode. Direct manual/shared bookmarks appear in Library after acquisition; shared saves display Bookmark added only after committed persistence.

Settings > Data Management > RSS uses the shared header and a Daily fetch time policy card with a daily master switch, Wi-Fi-only switch and full-width manual fetch button. Immediately below it, a separate Trigger time heading has a count and transparent + action; up to three chronological time cards show a minus action on the left and editable HH:mm time on the right. Empty schedules show Not set. Add/edit reuses the Material time picker and works while automatic fetching is OFF. Cards retain the existing 20dp rounded surfaceContainerHigh style; action touch targets are at least 48dp. The 20dp deletion circle uses the theme's DestructiveActionColors semantic pair (Material light error/onError in both themes) to preserve a saturated red background and white minus. The + action dims when disabled at the limit. The minus action opens a confirmation naming its time; Cancel or dismissal retains the schedule. Manual fetch displays a waiting label while its WorkManager request is queued and progress while it is running; the button remains disabled until the request finishes. An add FAB opens a bottom sheet with URL and retention sections. Feed cards show artwork or a monogram, publisher domain, marquee title, Direct and retention chips, errors and a separate enable switch. Tapping a card edits it; a labeled long-press action and the editor's delete action open confirmation. Selection rows combine labels and controls for accessibility. Notification lists import and RSS XML errors with Retry/Delete menus; RSS settings show fetch progress, and Library provides an RSS source filter. See [RSS and Atom Subscriptions](rss.md) for defaults, limits, and user-visible lifecycle behavior.

Library RSS mode uses the same `LibraryDeckCard` as Domain mode: a two-column grid, three stacked layers, identical spacing/typography/count badge, and a 96dp, alpha-0.20 icon watermark. RSS supplies feed title, publisher domain, visible saved count, and feed-specific artwork/color. Selecting a feed uses the shared Domain drill-down list/grid and ambient beam/radial-glow rendering, with a feed title/count header and Back to feeds action. Both back mechanisms return to the feed grid, after dismissing a summary peek if present. Crossfade timings match Domain mode (220ms in, 180ms out), and metadata-only refreshes do not restart that transition. Feed grids and detail layouts retain separate scroll positions. New RSS navigation/count strings are English only; subtitle counters use quantity resources for singular and plural feed/bookmark labels.

## 8. Multi-Theme Infrastructure & Centralized Stationary Background

### 8.1 Centralized Background Architecture (`TidiloBackground`)
Located in `ui/components/TidiloBackground.kt`:
- **Slot Placement**: Root `Box` of `MainActivity.kt` beneath `TidiloNavGraph`. Root `Scaffold` uses `containerColor = Color.Transparent` so the stationary background remains visible throughout the application.
- **Zero Route Sliding**: Route transitions slide only foreground UI content (cards, headers, list items); the root background remains anchored and stationary, eliminating visual cut-off seams.

### 8.2 Ambient Lighting Architecture (`LocalAmbientLighting` & `RememberAmbientLighting`)
Located in `ui/components/AmbientLighting.kt`:
- **Decoupled Ambient Rendering**: Library drill-down views (`DomainDrillDown`, `RssFeedDrillDown`, `CollectionDrillDown`) do not draw full-screen ambient lighting boxes inside scrolling content. Instead, `LibraryScreen` publishes the active drill-down color via `RememberAmbientLighting(color, ownerId = "library_drilldown")` to `LocalAmbientLighting`, while `BookmarkDetailScreen` retains its dedicated card-emanating `detailAmbientLight` and `detailBannerLight` modifiers.
- **Smooth In-Place Morphing**: `TidiloBackground` observes `LocalAmbientLighting.current.effectiveActiveColor`, resolves it through the active preset's `resolveColor(...)`, and animates color transitions in-place with `animateColorAsState(tween(350, easing = EaseInOutCubic))`.
- **Automatic Lifecycle Cleanup**: When `LibraryScreen` leaves drill-down mode or leaves composition, `RememberAmbientLighting` resets the active color on disposal via `DisposableEffect`.

### 8.3 Resolved theme and shared Surface contract

For preset registration, editing points, parameter limits, and verification steps, see [Developing app themes](theme_development.md).

`TidiloTheme` receives the selected `AppTheme` and resolves Material colors, Material shapes and `LocalThemeSurfaces` together. `AppConfig` continues to persist the existing `default` and `modern` codes locally. A theme change updates composition values without keying or recreating navigation, scrolling state or editor drafts.

- `AppThemeStyle` owns preset configuration, optional light/dark Material palettes, the named `ThemeShapeScale`, and per-role `SurfaceStyleOverride` values.
- Unspecified Material palettes preserve Dynamic Color. Modern provides fixed Material palettes for both appearances.
- `ResolvedThemeSurfaces.resolve` supplies complete foreground/background pairs, shape, treatment, shadow/elevation, edge and fill values. Explicit applied dark mode is authoritative; isolated MaterialTheme previews use one centralized fallback.
- `SurfaceTreatment` selects native or decorated drawing independently of elevation. A decorated surface at zero elevation retains its background, shape and edge. Shared click/long-click handling places shadows outside the content/ripple clip.
- `TidiloSurface` owns Material background suppression and custom drawing. Feature screens do not make containers transparent or disable shadows based on depth.
- Background opacity and existing self-blur are isolated from foreground content. `Modifier.blur` remains a self-blur hook, not a backdrop effect.
- `SurfaceStyleOverride` can tune shape, colors, opacity, elevation, border width, gradient stops, bevels and shadow colors for connected shared surfaces. Semantic error, matched/selected chip colors and selected expanded-filter color pairs take priority over neutral colors. Navigation animation re-applies final drawing overrides and uses the resolved shape and background opacity.
- `ThemeShapeScale` preserves existing 6/8/10/12/14/16/20/22/24/28/32/36dp values as named tokens. Standard Material shape defaults derive from the same scale. Specialized asymmetric image crops and feature lighting geometry remain explicit content treatments.

### 8.4 Component coverage and supported parameters

| Consumer family | Theme connection | Behavior retained |
| --- | --- | --- |
| Bookmark list/grid | BOOKMARK_CARD, compact grid variant | Scoped accent rim/wash/shadow, image crop, click/long press, paging keys |
| Collection/domain/RSS deck | DECK_FRONT and DECK_BACK | Existing front/back corner differences, stack offsets, watermark and contextual accent wash |
| Classification/entity display badges | Shared badge resolution and Surface | Category hierarchy, NSFW error pair and search-match colors |
| Entity editor/toggle chips | ENTITY_CHIP/FILTER_CHIP compact native styles | Existing flat styling, selection/removal, animated colors and limit state |
| Navigation, toolbar and actions | FLOATING_BAR, TOOLBAR, PRIMARY_ACTION, FILTER_ACTION, NAVIGATION_INDICATOR | Navigation/visibility animations, ripple and input semantics |
| Like and toolbar icon plates | LIKE_ACTION/TOOLBAR_ACTION wrappers | Existing foreground semantic tints, native icon/toggle interaction |
| Grouped settings/RSS/parameters | SETTINGS_GROUP and existing variants | Current flat Modern appearance, padding, disabled parameter treatment |
| Notification history and error rows | Material typography, dividers, and semantic error tone | Timestamps, history navigation, and Retry/Delete menus |
| Header and AI bubble | Resolved header/bubble attributes | Header fade curve and drag forwarding; bubble rainbow border and tail |
| Dialogs and bottom sheets | Resolved native container/default shape | Material focus, keyboard, scrim and dismissal; explicit component variants remain supported |
| Sort menu, custom tag input containers, badges and settings icon plates | MENU, INPUT, STATUS_BADGE, ICON_PLATE | Native behavior and existing semantic content |
| Remaining Material controls and diagnostic content | Preset Material palette and shared shape scale | Existing field/chip/menu defaults, error/disabled/focus behavior and specialized semantic colors |

Native Material consumers inherit their palette and shape defaults rather than supporting every custom decoration. Header and AI bubble retain specialized native drawing. A role override does not imply arbitrary custom rendering or layout support for those consumers. Component-specific typography, spacing, artwork and specialized lighting remain separate from shared surface decoration.

### 8.5 Modern preset and verification boundaries

Modern uses a fixed palette in both appearances. Dark uses a #1D1F20 canvas, #2B2C2D shared containers and a #D4B06B accent. Light uses a #F5F1E9 canvas, #FFFAF2 shared containers and a #886225 accent. The header shares the canvas base color and retains its scroll fade. Settings uses the same palette. Modern retains its 3dp tactile depth, 26dp card corners and 12dp badge corners.

Selected navigation labels, screen titles and enabled primary action labels can opt into a 1dp downward text shadow. Settings text, body copy and badges retain their regular styling.

Unit tests cover role resolution, zero elevation, semantic precedence, preset overrides, Material palette fallback and shape-scale propagation. Instrumentation fixtures use real components, actual painted-pixel assertions and theme switching with navigation/list/draft state. Building instrumentation does not prove visual parity: before/after capture, input, recreation and frame checks require a connected device. No whole-screen zero-overdraw or zero-GPU-cost guarantee is made.

Backdrop blur uses Haze 2.1.0 with independent effective policy. The app-wide modal source captures composited content; separate screen-owned body sources serve in-window chrome without capturing its own output. Source sharing is an ownership boundary, not a guarantee of one GPU replay. Relative frame measurements and their limits are documented in [chrome performance](chrome_blur_performance.md).


### Adaptive screen insets

`TidiloScreenScaffold` accepts explicit `contentWindowInsets = screenSystemInsets()` (system bars plus display cutout) and `avoidIme` for input screens. Its root/background/body capture source remain full-window. It subtracts ancestor consumption, provides safe horizontal margins and matching header clearance, and adds the remaining safe bottom inset to app-only `bottomContentPadding`. Lazy layouts use returned `contentPadding`; ordinary scrolling columns apply the returned padding after `verticalScroll`. Only the system-inset portion is consumed, not the header or action clearance.

Input bodies use one `imePadding` owner before their scrolling content. The preferred IME ruler was evaluated, but lateral navigation produced a narrowed Search body; the padding alternative preserves stable transition geometry. Body content insets exclude the part already covered by IME, restoring navigation clearance when the keyboard closes. `safeGesturesPadding` alone does not protect an input from IME. Backgrounds and headers are not fitted to the keyboard.

Shared header foreground rows avoid top and horizontal system/cutout regions while their frost remains full width. Floating actions use independent `screenActionInsets`. Library reserves 56dp action + 24dp margin + 24dp gap; AI Review Success reserves 86dp + 16dp + 16dp; RSS reserves 56dp + 16dp + 16dp. These app clearances are additional to safe system insets; loading/error states without a floating action do not reserve it.

Detail retains full-width hero artwork and independently safe text/control geometry. Its lower 54dp toolbar uses 31dp bottom margin and 16dp content gap. While IME is visible, lower actions are hidden and their reserve is removed; top Back/Save remain available. After dismissal, lower actions and clearance return. Search clears its focus/IME when opening Detail so returning does not reopen the keyboard.

Material3 sheets/dialogs retain native inset and IME ownership. Do not remove padding based on modifier coexistence alone, nor pass the Activity's content padding into another window. The current screen shell has no bottom app bar, so navigation contrast enforcement remains at its native default. MainActivity retains ComponentActivity `enableEdgeToEdge` and `adjustResize`; the non-visual share receiver does not compose a screen.
