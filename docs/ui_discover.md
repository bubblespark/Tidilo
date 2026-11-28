# Discover Home UI

The Discover home is the rediscovery-first start layer. It is shown only while the debug flag `AppConfig.discoverPreviewEnabled` is on (see [library_navigation.md](library_navigation.md#discover-preview-debug-flag)). Its picks come from the rediscovery engine ([rediscovery.md](rediscovery.md)).

Code: `ui/screens/discover/` — `DiscoverScreen.kt` (screen and header), `DiscoverShelves.kt` (one composable per shelf), `DiscoverCards.kt` (cards, captions, import cards), `DiscoverPlate.kt` (plates and posters), `PlateGeometry.kt` and `DiscoverText.kt` (pure helpers), `DiscoverPeekSheet.kt`, `DiscoverMotion.kt` (reduced motion, ambient, flip, entrance), and `DiscoverViewModel.kt`.

## 1. Plates and posters

- Every image surface is a **1.91:1 plate** (og:image design ratio). Images within 5% of 1.91 fill the plate exactly. Any other ratio is fitted (`ContentScale.Fit`) on a mat made from the domain tone (or the hero's edge colors), and is never cropped.
- Sizes (`PlateSize`, dp): XL full width (Today's pick), L 380 (Sleeping gems feature), L′ 324 inside a 340 postcard (deck), M 280 (On this day), S 200 / S′ 182 two-up (Just saved, Rising, Connections), XS 112 (list rows). Plates under 190dp use `shapes.medium`, larger ones `shapes.large`.
- **Posters** stand in for missing og:images. The background is a gradient from the domain's calibrated color. The text is white or ink, whichever keeps more contrast against both gradient stops, and the text region is darkened until every stop reaches 4.5:1 (`posterInk`). The layout variant comes from the bookmark id (`posterVariant`): title with a watermark initial, a colored spine, or a masthead rule. Compact posters (list rows) show only the initial, and the watermark initial fits its box, so it does not grow with the font scale.
- Captions, reasons, and labels sit outside the art. Domains are shown as display names, never as raw URLs.

## 2. Layout

A `LazyColumn` with one keyed item per non-empty shelf. Spacing is 40dp between shelves, 12dp from a shelf header to its content, and 16dp margins. The bottom padding clears the floating toolbar (`HeaderDefaults.FloatingNavigationClearance` plus the system inset). Shelf titles are `titleLarge` accessibility headings, and empty shelves take no space.

| Shelf | Layout |
| :--- | :--- |
| Today's pick | Matted cover. The masthead (menu, date, greeting) sits on the top mat; the full-bleed plate dissolves into mats from its own top and bottom edge colors (`rememberDetailHeroPalette`); the caption on the bottom mat has a reason chip, byline, `headlineMedium` title, and a two-line pull quote. The cover runs under the status bar. |
| On this day | Snapping row of M cards with a `titleLarge` period overline ("A year ago") |
| Just saved | Snapping row of S cards. Running imports (manual only) come first as a skeleton plate with `ContainedLoadingIndicator`, then failed imports with Retry, then saved bookmarks |
| Shuffle deck | A postcard (paper surface, postmark with the save month beside the byline, off the art) over two cards tilted ±3° (capped at 340dp and narrower windows), with Next card and Like buttons. After the last card, "Deal another" deals a new draw. The deck announces "Card N of M". Interactions in §5 |
| Sleeping gems | One feature (reason overline, plate, `titleLarge` title, pull quote) and up to four XS rows |
| Rising | A tonal topic card per entity (tap opens Search with the entity), then its row of S cards |
| Fresh from RSS | XS rows with an expiry chip |
| Connections | The shared entity pill, two dashed connectors, and the pair two-up |
| Your map | Entity bubbles sized and tinted by affinity; a tap opens Search with the entity, a long-press mutes it (§5) |

The snapping rows are `LazyRow`s with a fixed item width instead of `HorizontalUncontainedCarousel`. The carousel needs a fixed height, which clips captions at large font scales, while the row keeps the same uncropped plates.

Header: the date (`labelLarge`, local format, refreshed at midnight and on resume) and a one-line greeting: "Start with this one" when there is a Today's pick, "Your shelves fill in as you save" otherwise, and none for an empty library.

## 3. Cold start

- **0 items**: an empty wall under a soft spotlight, with an explanation and Save URL (opens Manual Import).
- **1–49 items**: Just saved, the deck, and a "How to save" card, plus any shelf whose rule already matched. Today's pick needs 50 items and Your map needs 30 (engine thresholds).
- **First appearance**: the first time a shelf (other than Just saved and the deck) appears after Discover recorded its baseline, its header (for Today's pick, the caption mat) shows "New shelf: …" once. One caption shows per visit; other new shelves keep theirs for a later visit. The shelves already seen are stored in `tidilo_library_prefs` (`discover_seen_shelves`). The very first visit only records the baseline, so an existing library shows no captions.

## 4. Engagement

- A card tap opens Bookmark Detail with the card's shelf as the route's `surface` argument (`bookmark_detail/{id}?surface=…`). Detail records its single `DETAIL` event with that surface (`EngagementSurface.DISCOVER_*`); other callers default to `DETAIL`. The card's plate grows into the Detail hero and returns to it on Back (`docs/ui_design.md` §4); the Peek sheet's plate takes no part.
- An entity tap replaces the Search query with `#entity` (`LibraryViewModel.searchEntity`).
- A deck Like is written by `DiscoverViewModel` with `DISCOVER_DECK`. The heart flips at once, then shows the stored state; a failed write or a deleted bookmark restores it. Next card records `DECK_FLICK`.
- Hiding Private content removes Private cards from the dealt deck at once. The first save into an empty library deals the deck.
- **Impressions**: a card that stays at least 50% visible for one second records `IMPRESSION` (`Modifier.onVisibilityChanged`). The repository keeps at most one per item per Discover day.
- Each card merges its title, reason label, and domain into one content description.

## 5. Interactions

- **Deck**: the top postcard follows a horizontal drag and tilts 0.06° per pixel. A fling faster than 1,000 dp/s or a drag past 40% of its width flicks it off (`GestureThresholdActivate` haptic) and records `DECK_FLICK`; anything shorter springs back (`motionScheme.fastSpatialSpec`). Drags are horizontal only, so a vertical swipe on the postcard still scrolls Discover. Double-tap likes the card with a small heart burst. TalkBack gets the custom actions Next card and Like / Remove like, plus Open (click) and Peek (long-press), and the state "Card N of M".
- **Peek sheet** (Discover only; Library keeps Summary Peek until the Library layer is restyled): a long-press on any card lifts it (scale 1.03 while pressed, `LongPress` haptic) and opens `TidiloModalBottomSheet`. The sheet blurs the screen behind it, or falls back to the opaque surface when blur is off. It shows the plate, reason, title, byline, and summary, with Like (`DISCOVER_PEEK`), Not now (30 days), Don't show again, and Add to collection. Opening records `PEEK` with `DISCOVER_PEEK`. Not now and Don't show again close the sheet, take the card off every shelf (a dealt deck card leaves the deck at once), and show a snackbar with Undo, which clears the suppression and puts a deck card back in its place. Add to collection opens the membership sheet, whose New collection opens the editor with the bookmark assigned. After the sheets close, input focus returns to the card (keyboard and D-pad; touch mode has no input focus).
- **Re-deal**: pull down at the top (`PullToRefreshBox`, `SegmentTick` haptic when the pull crosses the threshold) re-deals today's snapshot shelves (`DiscoverRepository.reDeal`). A pull during a running re-deal is ignored. Cards on Today's pick, On this day, Sleeping gems, Rising, and Connections turn face-down (rotationY 0° → 90°), then the new picks deal in from −90°, shelves staggered 30 ms. Just saved, the deck, Fresh from RSS, and the map do not flip.
- **Map mute**: a long-press on a bubble (`LongPress` haptic; TalkBack action "Mute topic") mutes the entity for rediscovery. The map, Rising, and Connections drop it at once, and the snackbar's Undo unmutes it.

## 6. Motion and reduced motion

- **Ambient bleed**: the shelf nearest the viewport center lights the app background with its first plate's domain color (owner `discover`; `MainActivity` allows ambient light on the Discover route). The background layer crossfades it (350 ms) and reads it in its draw phase.
- **Hero parallax**: the Today's pick plate trails the scroll at 0.32× (capped at 200dp) and slides under the caption mat.
- **First-open entrance**: on the first open of a Discover day (stored as `discover_entrance_day` in `tidilo_library_prefs`), the shelves rise 24dp and fade in, staggered 40 ms. Returning to Discover later that day does not replay it.
- **Reduced motion** (`ValueAnimator.areAnimatorsEnabled()` is false, which includes "Remove animations"): no ambient bleed, flip, parallax, entrance, or heart burst. Deck flicks still work and advance without the throw animation.

## 7. Verification

JVM tests: `DiscoverTextTest` (reason plurals, shelf titles and surfaces, plate math, poster contrast), `DiscoverViewModelTest` (cold start, import ordering and Private filtering, first-appearance captions, deck reducers, hide and Undo, Peek, mute, re-deal, entrance, impressions), and `DiscoverMotionTest` (center item). Instrumentation: `DiscoverShelvesTest` renders the screen from a fake source and checks the empty wall, the small-library layout, every shelf and its announcement, taps and entity navigation, import cards with Retry, deck buttons, flicks and accessibility actions, the Peek sheet with snooze/hide/Undo, Like, Add to collection and focus return, map mute with Undo, pull-to-re-deal, impressions, and Japanese text in the light theme at 2× font scale. `SettingsDiscoverResetTest` covers the reset entry.
