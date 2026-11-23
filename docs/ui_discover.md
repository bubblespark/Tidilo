# Discover Home UI

The Discover home is the rediscovery-first start layer. It is shown only while the debug flag `AppConfig.discoverPreviewEnabled` is on (see [library_navigation.md](library_navigation.md#discover-preview-debug-flag)). Its picks come from the rediscovery engine ([rediscovery.md](rediscovery.md)).

Code: `ui/screens/discover/` — `DiscoverScreen.kt` (screen and header), `DiscoverShelves.kt` (one composable per shelf), `DiscoverCards.kt` (cards, captions, import cards), `DiscoverPlate.kt` (plates and posters), `PlateGeometry.kt` and `DiscoverText.kt` (pure helpers), and `DiscoverViewModel.kt`.

## 1. Plates and posters

- Every image surface is a **1.91:1 plate** (og:image design ratio). Images within 5% of 1.91 fill the plate exactly. Any other ratio is fitted (`ContentScale.Fit`) on a mat made from the domain tone (or the hero's edge colors), and is never cropped.
- Sizes (`PlateSize`, dp): XL full width (Today's pick), L 380 (Sleeping gems feature), L′ 324 inside a 340 postcard (deck), M 280 (On this day), S 200 / S′ 182 two-up (Just saved, Rising, Connections), XS 112 (list rows). Plates under 190dp use `shapes.medium`, larger ones `shapes.large`.
- **Posters** stand in for missing og:images. The background is a gradient from the domain's calibrated color. The text is white or ink, whichever contrasts more, and the text region is darkened until it reaches 4.5:1 (`posterInk`). The layout variant comes from the bookmark id (`posterVariant`): title with a watermark initial, a colored spine, or a masthead rule. Under 150dp only the initial is shown.
- Captions, reasons, and labels sit outside the art. Domains are shown as display names, never as raw URLs.

## 2. Layout

A `LazyColumn` with one keyed item per non-empty shelf. Spacing is 40dp between shelves, 12dp from a shelf header to its content, and 16dp margins. The bottom padding clears the floating toolbar (`HeaderDefaults.FloatingNavigationClearance` plus the system inset). Shelf titles are `titleLarge` accessibility headings, and empty shelves take no space.

| Shelf | Layout |
| :--- | :--- |
| Today's pick | Matted cover. The masthead (menu, date, greeting) sits on the top mat; the full-bleed plate dissolves into mats from its own top and bottom edge colors (`rememberDetailHeroPalette`); the caption on the bottom mat has a reason chip, byline, `headlineMedium` title, and a two-line pull quote. The cover runs under the status bar. |
| On this day | Snapping row of M cards with a `titleLarge` period overline ("A year ago") |
| Just saved | Snapping row of S cards. Running imports (manual only) come first as a skeleton plate with `ContainedLoadingIndicator`, then failed imports with Retry, then saved bookmarks |
| Shuffle deck | A postcard (paper surface, postmark with the save month) over two cards tilted ±3°, with Next card and Like buttons. After the last card, "Deal another" deals a new draw. The deck announces "Card N of M" |
| Sleeping gems | One feature (reason overline, plate, `titleLarge` title, pull quote) and up to four XS rows |
| Rising | A tonal topic card per entity (tap opens Search with the entity), then its row of S cards |
| Fresh from RSS | XS rows with an expiry chip |
| Connections | The shared entity pill, two dashed connectors, and the pair two-up |
| Your map | Entity bubbles sized and tinted by affinity; a tap opens Search with the entity |

The snapping rows are `LazyRow`s with a fixed item width instead of `HorizontalUncontainedCarousel`. The carousel needs a fixed height, which clips captions at large font scales, while the row keeps the same uncropped plates.

Header: the date (`labelLarge`, local format, refreshed at midnight and on resume) and a one-line greeting: "Start with this one" when there is a Today's pick, "Your shelves fill in as you save" otherwise, and none for an empty library.

## 3. Cold start

- **0 items**: an empty wall under a soft spotlight, with an explanation and Save URL (opens Manual Import).
- **1–49 items**: Just saved, the deck, and a "How to save" card, plus any shelf whose rule already matched. Today's pick needs 50 items and Your map needs 30 (engine thresholds).
- **First appearance**: the first time a shelf (other than Just saved and the deck) appears after Discover recorded its baseline, its header shows "New shelf: …" once. The shelves already seen are stored in `tidilo_library_prefs` (`discover_seen_shelves`). The very first visit only records the baseline, so an existing library shows no captions.

## 4. Engagement

- A card tap records `DETAIL` with the card's shelf as the surface (`EngagementSurface.DISCOVER_*`), then opens Bookmark Detail.
- A deck Like goes through `LibraryViewModel.toggleLike` with `DISCOVER_DECK`. Next card records `DECK_FLICK`.
- **Impressions**: a card that stays at least 50% visible for one second records `IMPRESSION` (`Modifier.onVisibilityChanged`). The repository keeps at most one per item per Discover day.
- Each card merges its title, reason label, and domain into one content description.

## 5. Verification

JVM tests: `DiscoverTextTest` (reason plurals, shelf titles and surfaces, plate math, poster contrast) and `DiscoverViewModelTest` (cold start, import ordering and Private filtering, first-appearance captions, deck, impressions). Instrumentation: `DiscoverShelvesTest` renders the screen from a fake source and checks the empty wall, the small-library layout, every shelf and its announcement, taps and entity navigation, import cards with Retry, deck buttons, impressions, and Japanese text in the light theme at 2× font scale.
