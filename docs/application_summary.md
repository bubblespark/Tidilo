# Application Summary

Settings > Data Management > Summary (`Screen.Summary`, route `settings_summary`) displays statistics stored and calculated on the device. Its Settings entry uses the Material Symbols Outlined Summarize vector (24dp, weight400, FILL0, GRAD0, ROND50), displayed through the shared 20dp icon helper inside the standard 40dp primary-container circle with 16dp text spacing. The screen never invokes AICore, downloads model assets, or sends telemetry.

## Inference usage

`AiCoreUsageRepository` serializes updates on IO through a mutex and durably commits version-1 JSON in the dedicated `tidilo_ai_usage` SharedPreferences file. It stores daily counters, a recording start date, pending attempt identifiers and start dates, and the latest quota detection. No prompts, URLs, responses, or bookmark identifiers are stored. Daily aggregates are retained locally; only the last seven dates are displayed.

Both direct `generateContent` call sites are wrapped: production import/AI review (`NORMAL`) and the debug prompt tester (`DEBUG`). Every actual SDK attempt, including retries, counts once. Model setup, availability checks, downloads, page fetching, output parsing, and bookmark saving do not count. A successful SDK return counts as success even if subsequent parsing or saving fails. Debug calls are included in totals and shown as a subtotal.

Each attempt belongs to its local **start date**, including completions after midnight or a timezone change. Total equals success + failed + cancelled + interrupted + pending. Caller cancellation propagates after bookkeeping; cancellation before SDK entry rolls back the reservation. Reservations that were pending at process death become interrupted on the next load, because their SDK outcome cannot be reconstructed. A process death in the narrow reservation-to-SDK interval is also treated as interrupted. Bookkeeping failures do not suppress inference results. Failed durable writes display a warning and can be retried; unreadable history displays an error rather than an invented zero and is not overwritten.

The first `PER_APP_BATTERY_USE_QUOTA_EXCEEDED` (27) detected for each start date preserves the calls started on that date at detection and the detection timestamp. Further detections for that date retain that first benchmark. The latest detection is historical information, **not remaining quota**: limits can vary and need not reset at local midnight. Concurrent pending calls are included in the benchmark; an unstarted reservation cancelled afterward is removed from that benchmark.

Seven bars show today and the preceding six dates, including recorded zero counts. Dates before tracking began show an em dash and accessible “Not recorded” descriptions. Existing counters are never reassigned after timezone or clock changes. While the screen is resumed, the next local midnight is scheduled with DST-aware arithmetic; date, time, and timezone broadcasts refresh the current day and timestamp formatting. These listeners and the timer are removed on pause/disposal.

## Collection counts

`BookmarkDao.observeCollectionCounts()` obtains active and trashed counts in one SQL statement and observes both tables. `BookmarkRepository` gates observation on initialization and supports retry after failures. Counts include manual and RSS sources, NSFW, and other filtered items; active bookmarks exclude trash. The trash denominator is the existing `MAX_TRASH_CAPACITY` (100). No complete collection is loaded for these totals. Summary itself adds no Room tables; RSS uses schema9 as described in [RSS subscriptions](rss.md).

## Cache storage

`CacheStorageRepository` walks `cacheDir` on IO without following symbolic links or creating directories. Missing/disappearing files are tolerated; other access failures become a retryable section error. This is a best-effort filesystem snapshot: concurrent downloads or deletions may change disk contents immediately afterward.

- Domain icons: PNG file count and all physical bytes under `domain_icons`, including markers and temporary files.
- Page images: WebP file count and all physical bytes under `og_images`, including each physical `latest_domain.webp` copy and temporary files.
- Other cache: physical regular-file bytes elsewhere under `cacheDir`.
- Total cache size: the sum of those three byte categories. SharedPreferences/database storage outside `cacheDir` is excluded.

Each byte category is formatted as comma-grouped KB plus MB with one decimal digit, using binary units (1 KB = 1,024 bytes; 1 MB = 1,048,576 bytes). Positive sub-KB sizes round up to 1 KB; MB uses the underlying byte count. Display rounding can make the sum of displayed rounded KB differ from the rounded total; underlying bytes reconcile exactly.

Storage is scanned on entry, every resume (including resume without stop), and manual refresh. It is not polled in the background. Loading and errors are distinct from a successful zero-size result; successful scans include a last-checked timestamp. Usage, collection, and storage errors remain independent.

## Validation

JVM coverage exercises concurrent attempts, quota snapshots, cancellation, midnight/timezone attribution, pending recovery, write failures/corruption, physical cache totals, byte formatting, and DST scheduling. Android tests exercise actual SharedPreferences recovery, Room invalidation/counts, symlink exclusion, Settings navigation/back, manual storage refresh, and resume without stop. Emulator tests do not invoke AICore.
