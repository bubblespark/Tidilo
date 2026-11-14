# Bookmark Detail & Tag Editor UI Specification

---

## 1. Detail Scaffold, Scrim & Floating Header (`BookmarkDetailScreen.kt`)

- **Fixed Chrome Placement**: Top **Floating Header Actions** (`DetailFloatingHeaderOverlay`) and the lower `54.dp` toolbar live outside the scrollable body inside `TidiloScreenScaffold` so both remain fixed during scroll.
- **Minimal Top Chrome**: Detail omits any `"Details"` text header bar across loaded, loading, error, and not-found states. A permanent top status-bar gradient scrim (`statusBars` height + `8.dp` soft feather) blends `topEdgeColor` (or theme `canvasColor` without `og:image`) to transparent without intruding into the banner body.
- **Floating Back & Save Buttons**: Two `48.dp` circular `TidiloSurface(..., blurBackground = true)` buttons (`FLOATING_BAR` / `PRIMARY_ACTION`) sit at top-left (**Back**) and top-right (**Save**, `R.drawable.ic_save`). Save stays visible on loaded bookmarks, uses `0.38f` alpha when `!hasChanges`, highlights as `PRIMARY_ACTION` when dirty, and shows a `CircularProgressIndicator` while `isSaving`.

---

## 2. Seamless Hero Banner (`og:image`), Lighting & Watermark Fallback

- **Full-Bleed `og:image` Placement**: Spans `fillMaxWidth()` directly beneath the status bar band (`min(maxWidth / calculateOgAspectRatio(w, h), 300.dp)`, no rounded corners or border rim). Resolved via `rememberOgImage` (`LocalCacheVisualSource`, unified with `BookmarkGridCard`).
- **Dynamic Edge Palette (`DetailHeroPalette`, `BookmarkDetailLighting.kt`)**: `OgImageManager` samples top ~15% (`topEdgeColor`), bottom ~15% (`bottomEdgeColor`), and dominant `accentColor` on `Dispatchers.Default`, calibrating contrast via `calibrateDetailEdgeColor` with a `350ms` `EaseOutCubic` transition (caching up to 128 palettes per source/generation).
- **Minimal Feathering & Extensions**: Top `12.dp` / bottom `14.dp` edges feather softly on the image; above it extends `topEdgeColor` (`Transparent -> 0.50f -> 1.0f`), and below it a downward wash (`bottomEdgeColor -> 0.35f -> Transparent`) hosts the metadata and title.
- **Ambient Bloom**: Radial bloom (`screenWidth * 1.05`) tracks the banner's root-space center on scroll alongside `detailAmbientLight`.
- **No-Image Fallback (`DomainWatermark`)**: When `ogImageUrl == null` or resolution fails (`ogState.resolved`), top clearance (`statusBars + 72.dp`) protects text from Back/Save, and `DomainWatermark` (`195.dp`, 50% saturation, `0.14f` alpha, `RoundedCornerShape(20.dp)`) renders at `Alignment.TopEnd` with domain ambient light.

---

## 3. Metadata, Entity Row, Summary, Toolbar & Footer

- **Above-Title Metadata**: Inside the downward extension (`20.dp` horizontal padding):
  1. **Source Attribution**: `Domain • Relative Time` (`DomainPolicyManager.getDisplayName(domain) ?: domain`, `TimeFormatter`) in `labelMedium` (`12.sp`, `onSurfaceVariant`), without favicon or browser-jump icon.
  2. **Classification Breadcrumb**: Plain `labelMedium` (`12.sp`, `onSurfaceVariant`, no chips, icons, or Private accent) formatted via `formatClassificationBreadcrumb` (`Category > Subcategory - Private` or subset); omitted with zero vertical gap when all are empty/false.
  3. **Title**: `titleLarge`, `FontWeight.Bold`, `onBackground`.
- **Below-Title Entity Row**: No `# Category & Tags` section title or classification chips. Left (`weight(1f)`) renders `FlowRow` of `# Entity` chips (`BookmarkEntityChipsFlow` in Modern, `AssistChip` in Default, `6.dp` H / `8.dp` V gaps) or a subtle `+ Add tags` placeholder (`R.string.detail_add_tags_placeholder`); right renders `IconButton(Icons.Outlined.Edit)` (`R.string.detail_edit_tags`). Wrap the row in `CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified)` to avoid 48dp spacing inflation.
- **Editorial Summary Callout & Notes**: Header uses `R.drawable.ic_summarize` (document/notes glyph). Body sits inside a callout with a `3.dp` start accent bar (`detailAccent` or `primary`) and a horizontal `surfaceVariant` wash (`0.42f -> 0.16f`). Vertical rhythm is `24.dp` between sections and `10.dp` between headers and containers.
- **Bottom Toolbar**: Rounded `54.dp` bar (`31.dp` bottom margin + `16.dp` content gap) containing **Trash**, **AI Summary**, **Collection star**, and **Like** in order, paired with a matching rounded-square browser button on the right (sole canonical browser launcher). Toolbar hides (and removes reserve) while the IME is visible.
- **Footer**: Full `bookmark.url` above right-aligned `Created: yyyy/MM/dd HH:mm` / `Updated: yyyy/MM/dd HH:mm` in `labelSmall` (`onSurfaceVariant.copy(alpha = 0.45f)`). `combinedClickable` copies URL to clipboard with `HapticFeedbackType.LongPress` and `detail_url_copied` toast.

---

## 4. Tag Editor Dialog & Entity Picker (`BookmarkTagEditDialog.kt`, `EntityTagPickerBottomSheet.kt`)

- **Parent Dialog (`BookmarkTagEditDialog`)**:
  - Manages **Category** (7), **Subcategory** (11), **Private Content Switch**, and **Entities**. When `AppConfig.isPrivateContentVisible` is OFF and the switch is ON, renders an inline warning below the switch explaining the bookmark will be hidden on save.
  - Entity header displays `(${selectedEntities.size}/${Tag.MAX_ENTITIES})` plus borderless `+` (`AddEntityDialog`) and `R.drawable.ic_list_alt` (bottom-sheet picker) actions; preview chips use `AssistChip` styling (`10.dp` corners) without delete icons.
- **Dropdown Menu Geometry**:
  - Category/Subcategory selectors wrap `Surface` and `DropdownMenu` in `BoxWithConstraints(Modifier.fillMaxWidth())` and apply `Modifier.width(maxWidth)` + `Modifier.heightIn(max = 250.dp)` (~5 rows).
  - Dropdown container uses `surfaceContainerHighest` (contrasting against `AlertDialog`'s `surfaceContainerHigh`), `RoundedCornerShape(16.dp)` (`12.dp` field), and `1.dp` `outlineVariant` borders matching entity chips.
  - Passes dedicated `ScrollState` (`Modifier.verticalScrollbar` shown only when `maxValue > 0`: `4.dp` `primary.copy(0.15f)` track + `primary` thumb, `paddingEnd = 6.dp, paddingVertical = 12.dp`) to prevent invisible scroll continuation ("False Floor").
  - **Leading Checkmarks**: `Icons.Default.Check` (`18.dp`, `primary`) on selected rows; `Spacer(18.dp)` on unselected rows for fixed text alignment.
- **Entity Picker Bottom Sheet (`EntityTagPickerBottomSheet`)**:
  - Anchored at root `BookmarkDetailScreen` level (sliding over the dialog's lower half); OK uses `sheetState.hide()` before `onDismiss()`.
  - Top shows removable selected chips (`Icons.Default.Close`); bottom lists all library entities sorted via `compareEntityNames`. `SelectedEntityChip` and active `AllEntityToggleChip` share `primary` / `onPrimary` (`FontWeight.Bold`).
- **10-Entity Ceiling Feedback**:
  - At `Tag.MAX_ENTITIES` (`10`), tapping `+` blocks `AddEntityDialog`, unselected picker chips dim (`0.38f` text/border, `0.25f` fill), and an inline banner (`errorContainer.copy(0.35f)`, error `Info` icon, `detail_tag_entity_limit_inline`) appears without a modal dialog until a tag is removed or closed.
- **Two-Stage Persistence & Conflict Handling**:
  - Confirming the dialog updates `editedTag` / `editedIsPrivate` in memory only (updating the Detail preview and enabling Save); atomic Room commits (including staged cross-bookmark skeleton replacements across active/hidden bookmarks, excluding Trash) execute only on Detail Save with duplicate-tap protection.
  - Library skeleton conflict lookup collects only while manual entry or picker is open (blocks confirmation while loading; offers Retry on failure). Conflicts prompt **Use existing tag** vs. **Replace across all bookmarks** and preserve pending edits cleanly across state restoration (`SaveableStateRegistry` verified) and incoming Room updates.
