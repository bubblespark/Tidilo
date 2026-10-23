# Application Summary Implementation Plan

**Goal:** Add Settings > Data Management > Summary with local inference usage, collection counts, and cache storage.

**Approved spec:** Issue #89 plus all proposals approved on 2026-10-23. Count actual inference attempts across import, review, and debug (including retries), not bookmark saves. Track success/failure/cancelled/interrupted/pending separately. Attribute outcomes to the local start date. Preserve the first quota-27 detection per day and show its count/time as historical information, never remaining quota. Show seven daily bars and distinguish pre-tracking dates from zero usage. Storage includes domain icons, OG images, other cache, and their total; OG latest copies count as files.

**Architecture:** A serialized SharedPreferences repository persists daily aggregates and pending attempt identifiers, without prompts/URLs/outputs or a new Room table. A common tracker wraps the two direct SDK generation call sites. Room supplies an atomic aggregate of active/trash counts. An IO scanner measures cacheDir without following symbolic links or creating directories. A SummaryViewModel separates usage, counts, and cache loading/errors; visible lifecycle callbacks handle midnight/timezone changes and explicit cache refresh.

## Tasks

- [x] Add failing usage/tracker and filesystem/formatting tests, implement persistent aggregates and cancellation-safe accounting, then verify green.
- [x] Wrap production/review and debug generation calls; initialize usage tracking on application startup. Keep model/session ownership and inference results unchanged.
- [x] Add a reactive SQL count projection and repository observation without materializing bookmarks or trash.
- [x] Add Summary route, entry card, view model, three sections, seven-day bars, refresh timestamp/action, and English localized resources.
- [x] Verify process recovery, concurrency, quota snapshots, date boundaries, cache filesystem behavior, SQL counts, navigation/UI, and all JVM tests. Build/install debug on the first device; no emulator AICore inference.
- [x] Obtain independent review, update specs/task history, allocate v0.5.8/code39 once for this PR after checking main version, and commit locally. No push without approval.

## Review focus

- Only the SDK call is tracked; model setup/page fetch/parsing/storage are outside the count boundary.
- Cancellation records one terminal outcome before propagating; old pending records become interrupted on process restart.
- Recorder failures do not suppress or replace inference results/errors; storage reliability is visible.
- Midnight/DST/timezone changes refresh the visible day; no background polling of the filesystem.
- Cache sums include physical duplicates/markers/temp files but count only PNG/WebP image files. Disappearing files are tolerated; access errors are not reported as zero.
- Initial/loading/error count states must not masquerade as an empty library. Collection counts ignore search/NSFW visibility filters.

## Ledger

- Latest main and baseline JVM tests verified. Branch feat/application-summary; existing clean checkout reused.
- Version target will be v0.5.8; versionCode follows main's current value plus one (not the provisional header value).

- Implemented the approved Summary feature and updated its UI, data, AICore integration, and structure documentation.
- Independent review found pre-SDK cancellation overcounting and resume-without-stop refresh gaps. Both were reproduced/covered and corrected; follow-up review found no important remaining issues.
- Final verification: 188 JVM tests passed (zero failures/errors/skips); direct Android instrumentation completed `OK (33 tests)` on emulator-5554/API36. Both debug APK builds passed; inspected the settled Summary screenshot. No AICore inference was executed.
- An existing domain-icon card test counted one extra download during an earlier full run; its isolated rerun and the final complete run passed without changes to icon code/tests.
- Version allocated once: v0.5.8, code39. Local commit only; push remains subject to user approval.
