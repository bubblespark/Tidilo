# Bookmark Detail & Tag Editor UI Specification

---

## 1. Detail Scaffold, Scrim & Floating Header (`BookmarkDetailScreen.kt`)

- **Fixed Chrome Placement**: Top **Floating Header Actions** (`DetailFloatingHeaderOverlay`) and the lower `54.dp` toolbar live outside the scrollable body inside `TidiloScreenScaffold` so both remain fixed during scroll.
- **Minimal Top Chrome**: Detail omits any `"Details"` text header bar across loaded, loading, error, and not-found states. A permanent top status-bar gradient scrim (`statusBars` height + `8.dp` soft feather) blends `topEdgeColor` (or theme `canvasColor` without `og:image`) to transparent without intruding into the banner body.
- **Floating Back Button**: One `48.dp` circular `TidiloSurface(..., blurBackground = true)` button (`FLOATING_BAR`) sits at top-left (**Back**). Detail has no screen-wide Save action and no discard prompt: Back always leaves immediately because every edit has its own persistence boundary (see [Persistence Boundaries](#5-persistence-boundaries)).

---

## 2. Seamless Hero Banner (`og:image`), Lighting & Watermark Fallback

- **Full-Bleed `og:image` Placement**: Spans `fillMaxWidth()` directly beneath the status bar band (`min(maxWidth / calculateOgAspectRatio(w, h), 300.dp)`, no rounded corners or border rim). Resolved via `rememberOgImage` (`LocalCacheVisualSource`, unified with `BookmarkGridCard`).
- **Dynamic Edge Palette (`DetailHeroPalette`, `BookmarkDetailLighting.kt`)**: `OgImageManager` samples top ~15% (`topEdgeColor`), bottom ~15% (`bottomEdgeColor`), and dominant `accentColor` on `Dispatchers.Default`, calibrating contrast via `calibrateDetailEdgeColor` with a `350ms` `EaseOutCubic` transition (caching up to 128 palettes per source/generation).
- **Minimal Feathering & Extensions**: Top `12.dp` / bottom `14.dp` edges feather softly on the image; above it extends `topEdgeColor` (`Transparent -> 0.50f -> 1.0f`), and below it a downward wash (`bottomEdgeColor -> 0.35f -> Transparent`) hosts the metadata and title.
- **Ambient Bloom**: Radial bloom (`screenWidth * 1.05`) tracks the banner's root-space center on scroll alongside `detailAmbientLight`.
- **No-Image Fallback (`DomainWatermark`)**: When `ogImageUrl == null` or resolution fails (`ogState.resolved`), top clearance (`statusBars + 72.dp`) protects text from the Back button, and `DomainWatermark` (`195.dp`, 50% saturation, `0.14f` alpha, `RoundedCornerShape(20.dp)`) renders at `Alignment.TopEnd` with domain ambient light.

---

## 3. Metadata, Entity Row, Summary, Toolbar & Footer

- **Above-Title Metadata**: Inside the downward extension (`20.dp` horizontal padding):
  1. **Source Attribution**: `Domain • Relative Time` (`DomainPolicyManager.getDisplayName(domain) ?: domain`, `TimeFormatter`) in `labelMedium` (`12.sp`, `onSurfaceVariant`), without favicon or browser-jump icon.
  2. **Classification Breadcrumb**: Plain `labelMedium` (`12.sp`, `onSurfaceVariant`, no chips, icons, or Private accent) formatted via `formatClassificationBreadcrumb` (`Category > Subcategory - Private` or subset); omitted with zero vertical gap when all are empty/false.
  3. **Title**: `titleLarge`, `FontWeight.Bold`, `onBackground`.
- **Below-Title Entity Row**: No `# Category & Tags` section title or classification chips. Left (`weight(1f)`) renders `FlowRow` of `# Entity` chips (`BookmarkEntityChipsFlow` in Modern, `AssistChip` in Default, `6.dp` H / `8.dp` V gaps) or a subtle `+ Add tags` placeholder (`R.string.detail_add_tags_placeholder`); right renders `IconButton(Icons.Outlined.Edit)` (`R.string.detail_edit_tags`). Wrap the row in `CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified)` to avoid 48dp spacing inflation.
- **Editorial Summary Callout & Notes**: Header uses `R.drawable.ic_summarize` (document/notes glyph). Body sits inside a callout with a `3.dp` start accent bar (`detailAccent` or `primary`) and a horizontal `surfaceVariant` wash (`0.42f -> 0.16f`). Vertical rhythm is `24.dp` between sections and `10.dp` between headers and containers.
- **Bottom Toolbar**: Rounded `54.dp` bar (`31.dp` bottom margin + `16.dp` content gap) containing **Trash**, **Summarize with AI**, **Collection star**, and **Like** in order, paired with a matching rounded-square browser button on the right (sole canonical browser launcher). Toolbar hides (and removes reserve) while the IME is visible.
- **Summarize with AI**: A stop badge and accessible state indicate when AI Analysis is disabled. Tapping in that state explains the setting and links to AI Analysis. Otherwise, tapping checks the current device and model in a cancellable dialog. Only a ready result opens the existing proposal review. The dialog offers model setup in Settings when applicable, or explains how to continue with the saved description, tags, or note. The dialog closes before navigating to Settings or proposal review. Retry is available after a failed check. Dismissal or return from Settings retains the original bookmark draft and scroll position. The action is unavailable during Collection sheet transitions.
- **AI Proposal Review**: One generation attempt belongs to each review entry and survives Activity recreation. For each attempt, including Retry, the service checks the saved mode and model before fetching the page and again before inference. The review explains when AI Analysis is disabled or the model is unavailable. Its Back to bookmark details action returns to the same Detail entry for manual edits or AI Analysis setup. After process death, a restored review entry with a prior attempt explains that the previous result is unavailable and offers Retry and Back to bookmark details for manual edits. Library and Search Summary Peek reads the saved description.
- **Footer**: Full `bookmark.url` above right-aligned `Created: yyyy/MM/dd HH:mm` / `Updated: yyyy/MM/dd HH:mm` in `labelSmall` (`onSurfaceVariant.copy(alpha = 0.45f)`). `combinedClickable` copies URL to clipboard with `HapticFeedbackType.LongPress` and `detail_url_copied` toast.

---

## 4. Tag Editor Dialog & Entity Picker (`BookmarkTagEditDialog.kt`, `EntityTagPickerBottomSheet.kt`)

- **Parent Dialog (`BookmarkTagEditDialog`)**:
  - Manages **Category** (7), **Subcategory** (11), **Private Content Switch**, and **Entities**. When `AppConfig.isPrivateContentVisible` is OFF and the switch is ON, renders an inline warning below the switch explaining the bookmark will be hidden after OK.
  - Entity header displays `(${selectedEntities.size}/${Tag.MAX_ENTITIES})` plus borderless `+` (`AddEntityDialog`) and `R.drawable.ic_list_alt` (bottom-sheet picker) actions; preview chips use `AssistChip` styling (`10.dp` corners) without delete icons.
- **Dropdown Menu Geometry**:
  - Category/Subcategory selectors wrap `Surface` and `DropdownMenu` in `BoxWithConstraints(Modifier.fillMaxWidth())` and apply `Modifier.width(maxWidth)` + `Modifier.heightIn(max = 250.dp)` (~5 rows).
  - Dropdown container uses `surfaceContainerHighest` (contrasting against `AlertDialog`'s `surfaceContainerHigh`), `RoundedCornerShape(16.dp)` (`12.dp` field), and `1.dp` `outlineVariant` borders matching entity chips.
  - Passes dedicated `ScrollState` (`Modifier.verticalScrollbar` shown only when `maxValue > 0`: `4.dp` `primary.copy(0.15f)` track + `primary` thumb, `paddingEnd = 6.dp, paddingVertical = 12.dp`) to prevent invisible scroll continuation ("False Floor").
  - **Leading Checkmarks**: `Icons.Default.Check` (`18.dp`, `primary`) on selected rows; `Spacer(18.dp)` on unselected rows for fixed text alignment.
- **Entity Picker Bottom Sheet (`EntityTagPickerBottomSheet`)**:
  - Anchored at root `BookmarkDetailScreen` level (sliding over the dialog's lower half); OK uses `sheetState.hide()` before `onDismiss()`.
  - While the picker is composed, the retained tag editor omits only its own background blur (`blurBackground = !showEntityBottomSheet`). Its source registration, tint, outline, native window and draft state remain intact; the picker keeps its own blur. After the closing animation removes the picker, the parent background blur fades in over 300 ms. Its foreground and outline remain sharp and opaque; reopening immediately suppresses the effect again.
  - Top shows removable selected chips (`Icons.Default.Close`); bottom lists all library entities sorted via `compareEntityNames`. `SelectedEntityChip` and active `AllEntityToggleChip` share `primary` / `onPrimary` (`FontWeight.Bold`).
- **10-Entity Ceiling Feedback**:
  - At `Tag.MAX_ENTITIES` (`10`), tapping `+` blocks `AddEntityDialog`, unselected picker chips dim (`0.38f` text/border, `0.25f` fill), and an inline banner (`errorContainer.copy(0.35f)`, error `Info` icon, `detail_tag_entity_limit_inline`) appears without a modal dialog until a tag is removed or closed.
- **Dialog Transaction & Conflict Handling**:
  - Category, subcategory, entity, Private, and staged cross-bookmark replacement choices are a draft until the parent dialog's **OK**. OK persists the whole edit atomically (`updateBookmarkTags`: replacements across active/hidden bookmarks, excluding Trash, plus this bookmark's classification and Private columns) and then closes. While saving, OK shows progress and OK, Cancel, Back, and scrim are disabled. A failure keeps the dialog and draft open with an inline `common_save_failed` message, and nothing is partially applied, so OK retries.
  - **Cancel**, Back, or scrim discard the entire draft, including selections accepted in the nested picker and staged replacements. The picker edits only the parent draft; it never writes bookmark tags itself.
  - Library skeleton conflict lookup collects only while manual entry or picker is open (blocks confirmation while loading; offers Retry on failure). Conflicts prompt **Use existing tag** vs. **Replace across all bookmarks** and preserve pending edits cleanly across state restoration (`SaveableStateRegistry` verified) and incoming Room updates.

---

## 5. Persistence Boundaries

| Interaction | Persistence boundary |
| --- | --- |
| Note | Typing updates the field immediately; the latest value autosaves 500 ms after typing pauses (`DebouncedAutosaver`), and immediately on focus loss or when Detail leaves composition. An empty note is valid. |
| Category, subcategory, entities, Private | Parent tag dialog OK (see section 4). |
| Like | Saved on toggle. |
| Collection membership | Saved on each checkbox change (see [Collections](collections.md)). |
| AI summary/tag proposal | Saved only by Apply on the review screen; only the fields taken from the proposal are written. |

- **Field-scoped writes**: Note autosave updates only the `note` column; tag OK updates only classification, Private, derived search columns, and entity occurrences; AI Apply updates only description and/or classification. No path rewrites the whole bookmark from a UI snapshot, so independent edits never revert each other, and cancelling the tag dialog never undoes a saved note or membership change.
- **Leaving mid-save**: Writes run under `NonCancellable` on the shared `LibraryViewModel` scope, so navigating away or rotating right after typing does not lose the last edit. The note draft also survives configuration changes and process recreation through `rememberSaveable`.
- **Edit intent, not value comparison**: The note follows the stored value until the user types; from then on it is *edited* and Room emissions never replace it until a write of the latest edit succeeds (`EditedFields` versions). A revert to an earlier value is still an edit and is written; a failed write keeps the draft visible and owed for Retry. All note writes share one FIFO lock (`LibraryViewModel.noteWriteLock`), so a disposed screen's final write never lands after its replacement's newer one.
- **Feedback**: `AutosaveStatusLine` below the note shows `common_saving`, then `common_saved` for about two seconds, using a polite live region. A failed write keeps the typed text and shows `common_save_failed` with **Retry**.
