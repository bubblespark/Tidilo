# UI Design & Material 3 Expressive Specification

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
Used for visual categorization chips:
- `TagTech`: `0xFF388E3C` (Green)
- `TagAI`: `0xFF7B1FA2` (Purple)
- `TagDesign`: `0xFFE64A19` (Orange)
- `TagNews`: `0xFF0288D1` (Blue)

### 1.3 Warning Colors (Semantic Amber/Gold)
Used for warning indicators, non-blocking fallback statuses, and AI Skipped notifications:
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
- **ExtraLarge / Pill (`CircleShape` / `36dp`)**: Floating navigation bar (`TidiloBottomBar`), fab-style pills, primary action buttons.

---

## 4. Motion & Animation Physics

Tidilo uses realistic spring physics rather than mechanical linear transitions:

### 4.1 Header & Bottom Bar Transitions
- **Animation Spec**: `spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)`
- **Behavior**:
  - When scrolling down (upward swipe): Header slides out upward; bottom bar slides out downward.
  - When scrolling up (downward swipe): Header slides in from above; bottom bar slides in from below.
  - Combined with subtle alpha fade (`fadeIn(200ms)` / `fadeOut(150ms)`).

### 4.2 Screen-to-Screen Navigation & In-Page State Transitions
  - **Screen-to-Screen Transitions (`TidiloNavGraph.kt`)**:
    - Fully symmetric lateral slide transitions with non-bouncy spring physics (`dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow`):
      - **Forward Hierarchical Navigation (Enter / Exit)**: Incoming screen slides in from right to left (`SlideDirection.Start`) + fade in (280ms); outgoing screen slides out toward left (`SlideDirection.Start`) + fade out (200ms).
      - **Backward / Pop Hierarchical Navigation (PopEnter / PopExit)**: Previous screen slides in from left to right (`SlideDirection.End`) + fade in (280ms); popping screen slides out toward right (`SlideDirection.End`) + fade out (200ms).
      - **Predictive Pop Navigation (PredictivePopEnter / PredictivePopExit)**: Explicitly overrides Navigation Compose default `scaleOut(0.7f)` with symmetric lateral slides (`slideIntoContainer(End)` and `slideOutOfContainer(End)`). Completely eliminates unwanted shrinking-to-center / dissolve artifacts during predictive back swipe gestures.
  - **Dynamic Back-Stack Direction & Mid-Transition Gesture Safety**:
    - **Zero-Hierarchy Clean Architecture**: Avoids static depth numbers or hardcoded screen names.
    - Transitions evaluate returning vs advancing dynamically: when `targetState.id == navController.previousBackStackEntry?.id`, the transition is definitively a backward/pop operation and slides toward `End` (rightward). Otherwise, forward hierarchical transitions slide toward `Start` (leftward).
    - **Mid-Transition Back Swipe Collision Prevention (Fixes Issue #29)**: When a back gesture interrupts an in-flight forward entrance animation, Navigation Compose evaluates `enterTransition` / `exitTransition` instead of `popEnter` / `popExit`. Because the dynamic resolver checks `navController.previousBackStackEntry`, it accurately detects that the destination is the returning parent and enforces `SlideDirection.End`. This ensures the outgoing screen smoothly slides out to the right (→) and parent screen slides in from the left (→) without collision (`→ ←`) or blackout artifacts.
  - **Bottom Navigation Tab-Relative Direction**:
    - For floating navigation bar tabs (`Screen.allTabs`: `Library: 0`, `Search: 1`, `Import: 2`), the transition direction is determined by relative physical tab order rather than back-stack depth:
      - **Moving Right (`targetIndex > initialIndex`)**: `Library ➔ Search`, `Search ➔ Import`, `Library ➔ Import` slide with `SlideDirection.Start` (incoming enters from right).
      - **Moving Left (`targetIndex < initialIndex`)**: `Import ➔ Search`, `Search ➔ Library`, `Import ➔ Library` slide with `SlideDirection.End` (incoming enters from left).
      - Completely fixes the Navigation Compose artifact where `Import ➔ Search` was erroneously pushed as a forward (rightward) transition.
  - **Centralized Surface Background**: The full-screen background is managed centrally by the root `Scaffold` (`containerColor = MaterialTheme.colorScheme.background`) in `MainActivity.kt`. Because the lateral transitions maintain strictly adjacent (side-by-side) bounds with zero spatial overlap, individual screen roots avoid duplicate `.background()` modifiers, eliminating GPU overdraw while guaranteeing 100% seamless edge-to-edge visuals.
- **In-Page Content & Filter Transitions (`LibraryScreen.kt`)**:
  - Filter mode switching and Domain Grid ⇄ Domain DrillDown utilize a lightweight crossfade (`fadeIn(220ms)` togetherWith `fadeOut(180ms)`).
  - Eliminates jarring lateral page jumps during filter changes, giving users a responsive in-place refresh / redraw feel.

---

## 5. Edge-to-Edge & System Insets

Tidilo implements the Android 15+ official Edge-to-Edge guidelines:

1. **Full-screen Surface**: `MainActivity` calls `enableEdgeToEdge()` and sets `contentWindowInsets = WindowInsets(0, 0, 0, 0)` on the root `Scaffold`.
2. **Scroll Immersion & Actual Scroll Tracking**:
   - Lazy lists occupy the full physical screen with `contentPadding` accommodating the top app bar (`statusBars + 76dp`) and the bottom navigation bar (`140dp`).
   - Content flows freely under the translucent status and navigation bars.
   - **`onPostScroll` Consumption-based Connection**: Controls only hide when content **actually consumes vertical scroll** (`consumed.y < -30f`), completely preventing false dismissals on screens with few or non-scrollable items (such as ImportScreen or empty Library). Reappears when content actually scrolls up (`consumed.y > +20f`) or is pulled down at the top overscroll boundary (`available.y > +20f`).
3. **Status Bar Scrim**:
   - When bars are hidden during scroll, a dynamic 4-stop vertical gradient scrim renders over the status bar:
     `listOf(scrimBg.copy(0.85f), scrimBg.copy(0.50f), scrimBg.copy(0.15f), Color.Transparent)`.
   - Protects white status bar icons (battery, clock, Wi-Fi) from clashing with bright scroll content while harmonizing with the theme background.
4. **IME Keyboard Insets**:
   - In `BookmarkDetailScreen`, keyboard handling binds directly to `WindowInsets.navigationBars.union(WindowInsets.ime)` with zero artificial spacers, ensuring seamless dismissal without lag or pop-in.

---

## 6. Reusable Component Specifications

### 6.1 `TidiloBottomBar` (Floating Pill Navigation & Separated Circular Action Button)
- **Navigation Pill Bar**:
  - **Dimensions & Centering**: Outer Container Height `68dp`, Inner Tab Button Height `56dp` (aligned with the contracted 56dp action button height for a unified horizontal baseline), Width `maxWidth * 0.58f` centered horizontally (`Alignment.Center`). Anchored via `.navigationBarsPadding().padding(bottom = 24.dp)` in `MainActivity.kt` to symmetrically adjust top and bottom boundaries by 8dp relative to the previous 84dp/16dp layout, preserving the exact vertical center position (58dp above navigation bars).
  - **Shape**: `CircleShape` pill with `10dp` elevation shadow on `surfaceContainerHighest` container.
  - **Dynamic Tab Expansion & Vertical Layout**:
    - Layout: Vertical `Column` with icons and labels below.
    - Selected tab: Expands with spring weight animation (`1.35f`), rendering downscaled `[25dp Icon]` (10% smaller, truncated from `28 * 0.9 = 25.2dp` to `25dp`) and `[10sp Label]` (10% smaller, truncated from `12 * 0.9 = 10.8sp` to `10sp`, with `14sp` lineHeight and `2dp` vertical spacer) within an active container capsule (`primaryContainer`).
    - Unselected tabs: Contract with weight `1.0f`, rendering full-sized `[28dp Icon]` only (label hidden, icon centered vertically in the 56dp inner pill button).
    - Bar outer width remains invariant across tab selections.
  - **Badge**: Dynamic red counter badge on `Import` tab indicating pending URLs in the Ready group.
- **Separated Circular Action Button** (Material 3 Expressive Filled Tonal Button):
  - **Design & Colors**: `CircleShape` filled with the exact active tab container color (`MaterialTheme.colorScheme.primaryContainer`), with uniform active content icon tint (`MaterialTheme.colorScheme.onPrimaryContainer`) across all screens, ensuring harmonious visual unity between the floating navigation capsule and the action button.
  - **Dynamic Scaling, Offset & Scroll Persistence**:
    - **Contracted Size (Navigation Visible)**: Medium **M size** equivalent (`56dp` container, `28dp` icon, elevation `6dp`). Offset shifted right (`offset(x = (barWidth / 2) + 14.dp + (56.dp / 2))`) with a clean 14dp gap from the navigation pill bar to completely eliminate overlap.
    - **Expanded Size (Navigation Hidden / Scrolled)**: Fine-tuned **`86dp` container**, **`40dp` icon** (+12dp enlargement tracking container expansion), elevation `10dp`. Center offset anchors cleanly to standard FAB margin (`(maxWidth / 2) - 16.dp - (86.dp / 2)`).
    - **Perfect Circle Guarantee**: Outer `BoxWithConstraints` expands to `containerHeight = maxOf(barHeight, buttonSize)` with `requiredSize(buttonSize)`, preventing parent height constraints from clamping the vertical dimension to `barHeight` (68dp) and ensuring a pristine 1:1 circular aspect ratio (`86dp × 86dp`) in all states.
    - When navigation reappears (scroll up): Smoothly contracts back to 56dp container / 28dp icon with low bouncy spring animation.
  - **Contextual Behavior**:
    - `LibraryScreen`: Filter FAB menu toggle button (`FilterListIcon` / `Icons.Default.Close`). Expands a Material 3 Expressive pill menu with two persistent options: "Domain" on top (`DomainIcon`, 28dp) and "Newest first" below (`ArrowDownwardIcon`, 28dp). The active mode pill is highlighted using `primaryContainer` / `onPrimaryContainer`, and inactive using `secondaryContainer` / `onSecondaryContainer`. The pill size matches `BookmarkDetailScreen`'s FAB menu exactly (`horizontal = 24dp, vertical = 16dp`, typography `titleMedium`, SemiBold) and remains invariant regardless of the FAB container's 56dp/86dp scaling state, aligning flush to the right edge of the FAB. When expanded, the floating navigation bar hides downwards using official Edge-to-Edge slide-out animation, returning on dismissal.
    - `SearchScreen`: Search action button (`Icons.Default.Search`). Tapping requests focus to top search input bar and guarantees keyboard expansion (`keyboardController?.show()`) even if the input was already focused while the keyboard was dismissed. (Settings button on Search header removed).
    - `ImportScreen`: Filled accent sparkle button (`Icons.Default.AutoAwesome`) triggering batch `aiAnalysisAll()` execution. (Consists of 3 sections: Processing, Ready, Error; tapping 'AI Analysis All' promotes items directly to Processing without confirmation dialogs, while individual items are operated via swipe gestures).

### 6.2 `BookmarkItemCard`
- **Shape**: `RoundedCornerShape(24.dp)`.
- **Navigation Debounce Guard**: Incorporates single-click throttle protection (800ms debounce interval + `Lifecycle.State.RESUMED` navigation gate in `TidiloNavGraph`) preventing duplicate detail screens on rapid consecutive taps.
- **Hierarchy**:
  1. Title (with keyword highlighting when searching) + external browser icon button.
  2. URL (1 line, ellipsis).
  3. Description (AI-synthesized one-sentence summary).
  4. Tag row: `[Category] > [Subcategory]`.
  5. Entity tags: FlowRow of `CompactTagBadge` chips with `#` icon.

### 6.3 `CompactTagBadge`
- **Height**: Fixed `26.dp` container height (`Modifier.height(26.dp)`) with vertical center alignment, ensuring exact size uniformity between Japanese and English/Latin tags (e.g. NSFW badge, ASCII entity tags).
- **Shape**: `RoundedCornerShape(12.dp)`.
- **Layout**: 14dp Vector icon + 6dp spacer + label text (single line) with 10dp horizontal padding.
- **Hit Highlighting**: When matching active search queries, container and content transition to high-contrast tonal colors.

### 6.4 `BookmarkDetailScreen` & Material 3 Expressive FAB Menu
- **Layout Hierarchy**:
  - Root `Box(Modifier.fillMaxSize())`: Hosts full-screen scrollable `Column`, scrim overlay, and bottom-anchored FAB menu.
  - **Scroll Separation**: The FAB menu and backdrop scrim are placed **outside** the scrollable `Column`. Expanding the menu triggers zero unintended scroll jumps, and the toggle button never clips off-screen.
- **Shared `DomainWatermark` (Strict Non-Bleeding Placement)**:
  - Positioned **directly beneath the Top Header Row** inside a `Box(Modifier.fillMaxWidth())` at `Alignment.TopEnd` with `offset(x = 20.dp)` (offset precisely counterbalances parent `padding(horizontal = 20.dp)`).
  - **Zero Bleed-off**: Flushes completely against the physical right screen edge and top header bottom boundary without clipping outside the screen or encroaching into the header bar. Rendered using shared watermark parameters (`195dp`, 50% saturation, `0.14f` alpha, `RoundedCornerShape(20.dp)`).
- **Material 3 Expressive FAB Menu (`FloatingActionButtonMenu`)**:
  - **Parent Toggle FAB**: Conforms to the mandatory bottom-right action button standard (**86dp CircleShape container, 40dp icon, 10dp elevation, bottom = 16dp, end = 16dp margin**). Toggles between `MenuOpenIcon` and `Close`.
  - **Menu Items (`FabMenuItem`)**: Expanded dynamically to follow the enlarged 86dp FAB scale:
    - Shape: Single unified large pill button (`CircleShape`, `tonalElevation = 4.dp`, `shadowElevation = 6.dp`).
    - Internal Layout: `[28dp Icon] + [14dp Spacer] + [Label (titleMedium, SemiBold)]` with `horizontal = 24.dp, vertical = 16.dp` padding.
    - Vertical Stacking: Spaced by `14.dp`, right-aligned (`Alignment.End`) vertically with the parent FAB, expanding leftward dynamically based on label width.
  - **Actions**:
    - "AI Summary" (`secondaryContainer` / `onSecondaryContainer`).
    - "Delete" (`errorContainer` / `onErrorContainer`) prompting an `AlertDialog` before soft-deleting to 30-day Trash and navigating back.
- **Entity Tags Multi-Line Spacing**:
  - Wrapped in `CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified)` with `Modifier.padding(top = 8.dp)` to eliminate the unintended 16dp touch target vertical padding bloat between lines of wrapped entity chips, producing a crisp 8dp vertical line spacing across multiple rows while preserving the distance from the Category row.
- **Integrated Category & Tag Editing Workflow (Parent Dialog + Screen-Level Bottom Sheet)**:
  - **Edit Action Trigger**: Positioned at the right end of the "Category & Tags" section header row via a borderless `IconButton(Icons.Outlined.Edit)`.
  - **Parent Modification Dialog (`BookmarkTagEditDialog`)**:
    - Centralized `AlertDialog` managing Category (7 options), Subcategory (11 options), NSFW status toggle, and assigned Entities in one place.
    - **Category & Subcategory Dropdowns**: Single-select dropdown menus displaying localized strings.
    - **NSFW Content Toggle Section**: Positioned between Subcategory and Entity sections. Contains a Material 3 `Switch` allowing users to mark bookmarks as NSFW. When `AppConfig.isNsfwContentVisible` is OFF (NSFW hidden in Settings) and the toggle is ON, displays a contextual warning banner beneath the switch: `If you save while “ON” is selected, that bookmark will be hidden. (See “NSFW Contents” in Settings.)`.
    - **Entity Section Header**: Features borderless action icon buttons: `+` (Add new entity via `AddEntityDialog`) and `List Alt` (`ListAltIcon`, opens existing entity picker bottom sheet).
    - **Entity Preview Chips**: Rendered identically to Detail screen's chip style (`AssistChip`, 10dp rounded corners, tag icon) **without delete buttons** to maintain a clean layout.
  - **Screen-Level Bottom Sheet Anchor (`EntityTagPickerBottomSheet`)**:
    - Positioned at the root screen level (`BookmarkDetailScreen`) rather than inside the dialog's window tree.
    - Animates smoothly from the physical screen bottom, cleanly overlapping the lower half of the parent dialog.
    - Dismissal via the top-right OK button smoothly executes `sheetState.hide()` to slide down before invoking `onDismiss()`.
    - **Top Section**: Selected entity chips with dedicated delete ('x') buttons (`Icons.Default.Close`).
    - **Bottom Section**: All unique entity tags across the library sorted A-Z (`compareEntityNames`), functioning as toggle buttons with highlighted ON/OFF states.
    - Dismissing or dragging down cleanly commits changes to the parent dialog's draft state.
  - **Two-Stage Persistence & In-Memory Preview**:
    - Confirming the parent dialog updates `editedTag` and `editedIsNsfw` in memory, immediately previewing the changes on the Detail screen and activating the top-right "Save" button.
    - Changes are atomically persisted to Room database and repository only when the user taps "Save".
- **URL Interactive Row**:
  - Bound via `combinedClickable`. Single tap launches external browser with fallback toast; long press performs haptic feedback (`HapticFeedbackType.LongPress`), copies the full URL to the clipboard via `LocalClipboardManager.setText`, and displays an immediate confirmation toast.

---

## 7. Mandatory Design Rule: Bottom-Right Action Buttons & FAB Placement

Whenever a button or floating menu (FAB / Speed Dial) is placed at the bottom-right corner of a screen in Tidilo, it **MUST strictly adhere to the sizing, elevation, and positioning standards of the floating navigation action button**:

| State | Container Size | Icon Size | Shape | Elevation | Position / Margins |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Contracted (縮小時)**<br>*(When bottom navigation bar is visible)* | **`56dp`** | **`28dp`** | `CircleShape` | `6dp` | Placed adjacent to navigation pill with `14dp` clearance |
| **Expanded / Standalone (拡大時 / 単独設置時)**<br>*(During scroll / on screens without bottom nav, e.g. Detail Screen)* | **`86dp`** | **`40dp`** | `CircleShape` | **`10dp`** | `.navigationBarsPadding().padding(bottom = 16.dp, end = 16.dp)` at `Alignment.BottomEnd` |

### Expansion Rules for Sub-Menus (FAB Menu Items)
When a standalone 86dp bottom-right button expands into a menu (such as `BookmarkDetailScreen`), child menu pills must scale up in proportion:
- **Child Pill Container**: `CircleShape` with `horizontal = 24.dp, vertical = 16.dp` padding.
- **Child Icon**: Enlarged to **`28dp`**.
- **Child Typography**: Enlarged to **`titleMedium`** (16sp, SemiBold).
- **Vertical Alignment**: Right-aligned (`Alignment.End`) flush with the parent FAB right edge, with `14dp` vertical gap between items and `16dp` gap to the toggle FAB.

---

### 6.5 `DomainWatermark`
- **Dimensions & Effects**: Default `195dp` size, `ColorMatrix` with 50% saturation (`0.50f`), 0.14f alpha, clipped with `RoundedCornerShape(20.dp)`.
- **Shared Consistency**: Shared seamlessly between card feeds (`BookmarkCard`, `SearchScreen`), domain grid (`DomainCard`), and detail views (`BookmarkDetailScreen`).
- **Asynchronous & Non-Blocking Performance**: Uses `produceState<ImageBitmap?>` to resolve icons without blocking the main UI thread. In-memory hits (`DomainIconManager.getCachedIconBitmap`) render instantaneously (0ms), while cold disk cache reads are dispatched asynchronously on `Dispatchers.IO` (`DomainIconManager.loadIconFromDisk`), with redundant background transparency recalculations completely eliminated.

### 6.6 Dynamic Swipe-Action Import Cards (Ready & Error Groups)
- **Component**: Custom high-performance gesture layer utilizing Compose Foundation `Modifier.draggable(Orientation.Horizontal)` and `Animatable` layout offset, eliminating `SwipeToDismissBox` quirks and deprecations.
- **Directional Actions & Visuals**:
  - **Right Swipe (`StartToEnd`)**: Triggers immediate AI Analysis (`promoteToProcessing` for Ready, `retryError` for Error).
    - Container: Dark Green (`Color(0xFF2E7D32)`), `RoundedCornerShape(24.dp)`.
    - Content: Sparkle icon (`Icons.Default.AutoAwesome`, 26dp, White) aligned `CenterStart` with `24dp` horizontal padding.
  - **Left Swipe (`EndToStart`)**: Triggers immediate item deletion.
    - Container: Error Red (`Color(0xFFC62828)`), `RoundedCornerShape(24.dp)`.
    - Content: Trash icon (`Icons.Default.Delete`, 26dp, White) aligned `CenterEnd` with `24dp` horizontal padding.
- **Real-Time Finger Tracking & Release Mechanics (Gmail-Grade UX)**:
  - **Continuous 1:1 Tracking**: The card tracks the finger precisely in real-time. Actions are NEVER triggered while the finger is on screen.
  - **Under-Threshold Release**: When released below the 40% displacement threshold (and without high fling velocity), the card smoothly and physically springs back to the center (`0.dp`) via `Spring.DampingRatioLowBouncy` and `Spring.StiffnessMediumLow`, preserving release velocity with zero abrupt snapping.
  - **Over-Threshold Release / Fling**: When released past 40% (or on a natural finger flick), the card dynamically animates all the way off-screen in the swipe direction (`FastOutSlowInEasing`), allowing the user to visually follow the slide-out before the action executes and the list collapses.
- **Dynamic Tactile Icon Scale Feedback**:
  - Action icons (`Icons.Default.AutoAwesome` / `Icons.Default.Delete`) dynamically spring from `0.85f` to `1.25f` scale (`Spring.DampingRatioMediumBouncy`) the instant the drag crosses the 40% threshold, giving tactile confirmation of when release will trigger the action.
- **Immediate State Reset & Lifecycle Protection (Resolves Issue #27)**:
  - Once the card fully slides off-screen, executes the action callback and immediately resets `offsetX` to `0f`.
  - Guarded with `LaunchedEffect(item.id, item.status, item.statusText)`: Any card retained or returned to the ready group (e.g. AICore Quota Exceeded error 27 or deletion Undo) automatically resets to `0f`, completely eliminating off-screen stuck card states.
- **Undo Snackbar & Clean State Restoration**:
  - Displays with standard short duration (`SnackbarDuration.Short`) and an `Undo` button.
  - Positioned safely above the floating navigation pill: `Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 96.dp)`.
  - Tapping `Undo` restores the item to its exact pre-deletion index in the respective group.
  - **Scoped State Lifecycle (`remember(item.id)`)**: `SwipeToDismissBoxState` and internal trigger flags are explicitly scoped to `item.id` rather than using `rememberSaveable`. This ensures restored items re-initialize cleanly at `Settled` (center position), completely preventing the infinite loop bug where restored cards reappeared off-screen and triggered immediate re-deletion.
  - Rapid deletion replacement: Any ongoing Snackbar is dismissed instantly (`snackbarJob?.cancel()` + `snackbarHostState.currentSnackbarData?.dismiss()`) when a subsequent item is swiped away, immediately presenting the new item's Snackbar.
- **Screen Navigation Affordance Cue**:
  - On entry into Import Screen, if cards exist in Ready or Error groups, cards perform a micro bounce cue (`Animatable` jumping `24dp` rightward and settling via a medium-bouncy spring), teaching users swipe affordance naturally without intrusive tutorials.
- **Card Surface Simplification & Single-Line Title Truncation (Buttons Removed)**:
  - Individual action buttons on cards (`FilledTonalIconButton` with sparkle or retry) were eliminated across all groups (Processing, Ready, Error).
  - Cards delegate individual actions entirely to swipe gestures (right swipe to run AI / retry, left swipe to delete) and batch operations to the floating action button ('AI Analysis All'), maximizing title and URL display width while producing a cleaner, uncluttered card layout.
  - Page title text (`item.title`) is constrained to a single line (`maxLines = 1, overflow = TextOverflow.Ellipsis`), preventing long webpage titles from stretching cards vertically and ensuring consistent card heights across all task states.

### 6.7 NSFW Contents Settings & Badge Specification
- **Settings Screen Menu Icons (`SettingsScreen.kt`)**:
  - All menu card icon containers are unified to `primaryContainer` with icon tint `onPrimaryContainer` (`Language`, `NSFW Contents`, `Re-fetch Domain Icons`, `Debug`, `GenerateContentRequest`), ensuring visual consistency across all Material 3 Expressive settings cards.
- **NSFW Settings Screen (`NsfwSettingsScreen.kt`)**:
  - Clean top bar with `Icons.AutoMirrored.Filled.ArrowBack`.
  - Top options configured as 20dp rounded cards (`surfaceContainerHigh`):
    - Layout: Flattened `Row` structure (`Surface(Icon, 40.dp, primaryContainer/onPrimaryContainer)` + `Spacer(12.dp)` + `Column(Modifier.weight(1f))` + `Spacer(12.dp)` + `Switch`), with `padding(horizontal = 16.dp, vertical = 14.dp)` to maximize usable text width and eliminate mid-character line wraps before the Switch toggle.
    - **"Show NSFW Content" Switch**: Controls `AppConfig.isNsfwContentVisible`. Default ON.
    - **"Bookmark NSFW Content" Switch**: Controls `AppConfig.isNsfwContentBookmarkable`. Default ON.
  - **NSFW Domain Management Section**:
    - Lists all registered domains with a `Checkbox` on the left and domain name (with cached favicon/watermark icon) on the right.
    - Interactive row tap toggles domain NSFW classification in `DomainPolicyManager`.
- **Card & Detail NSFW Chip**:
  - `BookmarkCard` & `BookmarkDetailScreen` display a prominent red-toned `NSFW` badge (`errorContainer` background, `onErrorContainer` text) immediately adjacent to the `[category] > [subcategory]` breadcrumb when `bookmark.isNsfw == true`.

### 6.8 `DomainCard` & Library Domain Filter Mode
- **2-Column Grid Layout (`LazyVerticalGrid(GridCells.Fixed(2))`)**:
  - Horizontal/vertical arrangement with `12.dp` spacing, `20.dp` screen horizontal padding.
  - Content padding accounts for top header (`statusBars + 76dp`) and bottom navigation (`140dp`).
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
    - Display Name header: `titleMedium`, Bold, max 2 lines with ellipsis. Falls back to domain URL if empty.
    - Domain URL: `bodySmall`, high-contrast `onSurfaceVariant` color, single line with ellipsis.
    - Bookmark count badge: Pill surface badge (`dominantColor.copy(alpha = 0.20f)`) with bookmark icon (`Icons.Default.Bookmark`) and calibrated high-luminance count text (`"%d件"` / `"%d items"`).
- **Drill-down Interaction & Radiant Ambient Background**:
  - Tapping a domain card opens the domain-filtered bookmark list (rendered using standard `BookmarkItemCard` in latest-first order).
  - **Radiant Brand Background (2/3 Screen Spread)**:
    - Uses domain dominant color (`selectedDomainColor`), resolved asynchronously via `produceState` from `DomainIconManager.getCachedDominantColor` or fallback `generateDomainAccentColor`.
    - Radiant beam (`Brush.linearGradient`): 6-stop gradient radiating from top-right (`Offset(size.width * 1.05f, -size.height * 0.02f)`) towards bottom-left (`Offset(-size.width * 0.15f, size.height * 0.90f)`), holding visible color across ~2/3 of the screen before gently tapering (`0.32f` -> `0.22f` -> `0.13f` -> `0.05f` -> `0.015f` -> `Transparent`).
    - Corner radial glow (`Brush.radialGradient`): Centered at `Offset(size.width, 0f)` with deep radius `size.height * 0.72f` (~650-700dp), bathing the upper 2/3 of the screen in a soft atmospheric bloom (`0.22f` -> `0.13f` -> `0.05f` -> `0.012f` -> `Transparent`).
    - Seamlessly harmonizes with the card top-right ambient illumination, establishing deep spatial coherence.
  - Screen header shows domain display name with a back navigation arrow button (`Icons.AutoMirrored.Filled.ArrowBack`).
  - Header subtitle text color dynamically coordinates with `selectedDomainColor`.
  - System back gesture is captured via `BackHandler` to return to the domain grid rather than exiting or switching tabs.
  - **Drill-down Content State Retention during Exit Transition**: The drill-down bookmark list is strictly derived from the composable's `state.domain` via `LibraryViewModel.filterBookmarksByDomain(bookmarks, state.domain)` rather than the globally mutable `selectedDomainFilter`. This guarantees that exiting drill-down views retain their items and never flash a transient "No bookmarks yet" empty state while fading out during back gestures.
- **Page Transition Animations (`AnimatedContent` & `AnimatedVisibility`)**:
  - **Forward Transition (Domain Grid -> Domain Drill-down)**: Content enters with slide towards `Start` (`slideIntoContainer` with low-bouncy spring + 280ms cubic ease fade-in) and exits towards `Start` (no-bouncy spring + 200ms fade-out).
  - **Backward Transition (Domain Drill-down -> Domain Grid)**: Content enters with reverse slide towards `End` (low-bouncy spring + 280ms cubic ease fade-in) and exits towards `End` (no-bouncy spring + 200ms fade-out).
  - **Filter Mode Transition**: Smooth 240ms crossfade between `NewestFirst` and `DomainGrid`.
  - **Header Elements**: Back button expands/shrinks horizontally with alpha fade in `AnimatedVisibility`; header title and subtitle crossfade gracefully via `AnimatedContent`.
  - **Scroll Position Memory**: Grid scroll state (`domainGridState`) is preserved when popping back from a drill-down list, while the drill-down list resets to top (`scrollToItem(0)`) upon selection.

### 6.9 `FrostedHeaderContainer`
- **Purpose**: Eliminates the harsh, hard-edged cutoff line between the fixed/sliding header and scrolling list content in Edge-to-Edge screens.
- **Single-Canvas Architecture**: Replaced separate stacked boxes with a single `Column` background draw pass (`Modifier.drawBehind`), completely removing any physical layout seams or derivative discontinuities between the header container and list content.
- **14-Stop Smootherstep Attenuation Curve**:
  - Implements Ken Perlin's quintic smootherstep equation ($S(t) = 6t^5 - 15t^4 + 10t^3$, $\alpha(t) = 1 - S(t)$) sampled across 14 finely spaced gradient stops.
  - Because both first and second derivatives are zero at $t=0$ and $t=1$ ($d\alpha/dt = 0$, $d^2\alpha/dt^2 = 0$), the transition is optically seamless, entirely eliminating optical Mach banding.
- **Text-Adjacent Decay Proximity (`decayOverlap = 6.dp`, `fadeHeight = 18.dp`)**:
  - Rather than maintaining an opaque block below the text, decay starts right at the bottom edge / baseline of the header subtitle text ("かすれるか触れるくらい") and gently cascades downward over 24dp.
  - Combined with tightened header Row padding (`top = 4.dp, bottom = 4.dp`) and calibrated list content padding (`headerHeight = statusBarTop + 80.dp`), cards at rest sit completely clear of the dissolved header with zero overlap, smoothly entering the fade zone only when scrolled.
  - Status bar and title text retain 100% legibility contrast, while the bottom shelf dissolves organically into the underlying content.
- **Pure Neutral Translucency (No Color Overlays on Header)**:
  - Header relies exclusively on neutral frost translucency (`contentAlpha = 0.85f`) and smootherstep dissolution without applying artificial colored lighting layers, eliminating color collision and shelf artifacts.
  - The underlying screen's ambient illumination shines naturally through the translucent frosted glass with complete optical clarity.
- **Touch-through Transparency**: Lower dissolve zone uses a non-interactive `Spacer`, allowing pointer events to interact freely with underlying lazy lists.
- **Adopted Screens**: Unified across `LibraryScreen`, `SearchScreen`, and `ImportScreen`.

### 6.10 Application-Wide Title Bar & Header Geometry Standardization
- **Baseline Source of Truth (`LibraryScreen`)**:
  - Distance from Status Bar: `statusBarsPadding()` immediately followed by `top = 4.dp, bottom = 4.dp` padding.
  - Horizontal Screen Margin: `start = 20.dp, end = 20.dp`.
  - Title Text Typography: `MaterialTheme.typography.headlineMedium`, `fontWeight = FontWeight.Bold`, `color = MaterialTheme.colorScheme.onBackground`.
  - Vertical Center Line: Coordinated at $Y \approx 217\text{px}$ across Pixel / standard densities.
- **Back Navigation Affordance (`IconButton`)**:
  - Dimensions & End Spacing: `modifier = Modifier.padding(end = 4.dp).size(40.dp)`.
  - Icon Vector & Tint: `Icons.AutoMirrored.Filled.ArrowBack` with `tint = MaterialTheme.colorScheme.onBackground`.
  - Horizontal Title Text Alignment: Exactly $64\text{dp}$ ($20\text{dp} + 40\text{dp} + 4\text{dp}$) from the screen start edge across all screens with back navigation.
- **Secondary Actions & Toolbar Buttons**:
  - Header Action Buttons: Standardized to `modifier = Modifier.size(44.dp)` (e.g. Settings gear in Library, Add link in Import, Reset Wrench in GenerateContentRequest).
- **Unified Screens**:
  - Tab Screens without Back Button: `LibraryScreen`, `SearchScreen`, `ImportScreen`.
  - Sub-Screens with Back Button: `BookmarkDetailScreen`, `TagSelectionScreen`, `SettingsScreen`, `NsfwSettingsScreen`, `SettingsDebugScreen`, `GenerateContentRequestScreen`, `BookmarkAiReviewScreen`.

### 6.11 AI Skipped (AICore Fallback) Representation & Detail Layout Rhythm
- **Bookmark Card (`BookmarkCard.kt`)**:
  - Removed the standalone `AI Skipped` tag chip from the card tags row.
  - When `bookmark.isAiSkipped == true`, embeds the warning icon directly inside the summary text via `InlineTextContent` (`16.sp` with `PlaceholderVerticalAlign.Center`). Wrapped text lines naturally wrap underneath the icon flush with the left margin rather than creating an artificial empty column.
- **Bookmark Detail Screen Spacing & Layout Rhythm (`BookmarkDetailScreen.kt`)**:
  - Removed the `AI Skipped` assist chip from the Category & Tags section.
  - When `bookmark.isAiSkipped == true`, displays a clear warning notice below the URL surface: `[warning icon] This bookmark has not been analyzed by AICore.` using `WarningColor.textOrIcon` for both icon and text with 8dp horizontal spacing.
  - Spacing rhythm: URL to Warning notice is `6.dp`, Warning notice to Category & Tags is `14.dp` (eliminating disproportionate voids).
  - Category & Tags edit `IconButton` and tag chips (`AssistChip`) are scoped under `CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified)` to eliminate invisible accessibility touch-target inflation (which previously caused a 32dp gap), harmonizing the header-to-content gap across Category & Tags, Summary, and Note to a consistent `10.dp`.

### 6.12 Tag Edit Dialog Dropdown Menu Specification (`BookmarkTagEditDialog.kt`)
- **Full-Width Popup Anchoring**:
  - The Category and Subcategory selection fields wrap their `Surface` and `DropdownMenu` inside `BoxWithConstraints(modifier = Modifier.fillMaxWidth())`.
  - The `DropdownMenu` applies `Modifier.width(maxWidth)`, ensuring the dropdown menu spans the exact width of the parent selector field rather than clipping or floating arbitrarily to the left/right.
- **Bounded Menu Height & Smooth Scrolling**:
  - Menu height is constrained using `Modifier.heightIn(max = 250.dp)` (roughly 5 visible items).
  - Leveraging Compose's built-in `DropdownMenuContent` vertical scroll support, longer lists (such as Subcategory with 11 items) scroll smoothly without extending off-screen or obscuring the dialog layout.
- **Visual Contrast & Boundary Definition**:
  - Since `AlertDialog` sits on `surfaceContainerHigh`, dropdown container color is elevated to `MaterialTheme.colorScheme.surfaceContainerHighest`.
  - Applies a 1dp (1pt) border with `MaterialTheme.colorScheme.primary` and `shape = RoundedCornerShape(16.dp)`, providing crisp boundary definition and brand harmonization with dialog accents.
- **Scroll Affordance & Vertical Scrollbar Indicator**:
  - Solves the "False Floor" (invisible scroll continuation) issue using a custom dynamic vertical scrollbar (`Modifier.verticalScrollbar`).
  - Passes dedicated `ScrollState` (`categoryScrollState` / `subcategoryScrollState`) to `DropdownMenu(scrollState = ...)`.
  - Only renders when `scrollState.maxValue > 0` (non-scrollable lists remain clean with no extra chrome).
  - Displays a subtle 4dp primary track (`primary.copy(alpha = 0.15f)`) and dynamic primary thumb (`primary`) on the right edge (`paddingEnd = 6.dp, paddingVertical = 12.dp`), providing immediate visual cues on scrollability and current scroll position.
- **Leading Checkmark Alignment**:
  - Moved checkmark icon from `trailingIcon` to `leadingIcon` (display order: `[Checkmark] [Item name]`).
  - Selected items render `Icons.Default.Check` (`size = 18.dp`, tinted with `primary`).
  - Unselected items render an empty `Spacer(modifier = Modifier.size(18.dp))` in `leadingIcon`, maintaining uniform vertical text alignment across all menu entries.

