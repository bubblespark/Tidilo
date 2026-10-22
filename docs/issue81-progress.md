# Issue #81: Room-backed Paging verification

## Status and implementation

Implemented on `mod/room-backed-paging`, based on main `56d9a35`. Application
version: **0.5.5 (code 36)**. Review revisions keep the same issue version.

- Removed the active-bookmark full-list observer/cache and eager DAO reads.
- Timeline, domain drill-down, and search use Room PagingSource with deterministic
  `createdAt DESC, id DESC` ordering, SQL NSFW filtering, and stable ID keys in
  list/grid layouts. Search follows the shared library view-mode preference.
- Pager generations switch on query/domain/visibility before cachedIn; loading
  generations prevent presenting old-filter data as new-filter results.
- Page size 30, prefetch 10, initial size 60, max size 150, no placeholders.
  An actual Room/Paging integration test traverses 300 rows, checks retention
  after each append, reloads evicted earlier pages, and observes/edits an off-page ID.
- Domain/tag aggregates, distinct settings domains, and single-ID detail flows
  replace full-object reads. Tag counts never depend on loaded pages.
- Note/field/domain changes target durable rows. Trash insertion, active deletion,
  and capacity enforcement share one transaction. Import saving stays awaited.
- Initial/empty/error states are distinct. Initialization and aggregate failures,
  refresh failures, and both prepend/append failures expose retry. Detail reads
  distinguish loading, missing, and failure; saves navigate only after success.
- Domain NSFW changes finish their SQL/policy steps in the application scope,
  including when the settings composition is disposed.
- Startup icon preparation reads distinct domains and at most two 64-URL batches,
  preserving fallback and transient-failure negative-cache behavior.

## Search and tag compatibility

Search binds literal substring parameters over derived comparison keys. Tokens
use full-width-space normalization and AND semantics; ordinary fields are title,
description, and URL. Tag tokens match category/subcategory or individual entity
occurrences, including Japanese/English labels. No FTS or tokenization changes.

The entity relation preserves duplicates and case-sensitive count buckets. Derived
values mirror Tag.create: trim whitespace/one leading #, drop blank entities,
apply None defaults, preserve genuinely empty AI-skipped tags. Raw columns are
unchanged. Normalized category/subcategory buckets merge equivalent raw spellings.

An Android SQLite probe reported version 3.44.3 and showed LIKE differs from Kotlin
for Turkish I. The comparison key uses uppercase-then-lowercase simple folding,
without expansion or normalization. The Android runtime also differs from newer
JDKs on supplementary-letter ignoreCase comparisons: Android compares UTF-16 units.
The key follows the running String implementation, and all Unicode scalar case
mappings are checked on both JVM and Android. SQL fixtures additionally test these
letters, Japanese, sigma, dotted/dotless I, literal percent/underscore, empty and
standalone # queries, mixed tokens, and cross-entity-boundary exclusion.

## Migration and query plans

Room v6 upgrades to v7 in stable-ID batches of 256, updating only derived fields
and child rows before readers are exposed. v3-v6 upgrade paths are explicit;
there is no destructive fallback. Active bookmarks, trash, and import-task original
columns are fingerprinted before/after migration with bounded-memory SHA-256.
Invalid URLs keep a null domain and remain searchable/in the timeline.

SQLite application_id is reserved for the derived format (TID7/TID8 according to
runtime comparison behavior). A transactional onOpen callback repairs older
prerelease-v7 derived data once, including the marker, without changing raw fields
or Room schema identity. Reopen tests verify both repair and skipping repeat work.

Representative migration/open/count measurements on API 36: **199 ms for 1,000**
and **2,005 ms for 10,000** bookmarks. This includes opening, migration, validation,
and the first count. The migration fixture includes raw whitespace/# tags and
blank/duplicate entities. It is separate from application startup measurements.

At 10,000 rows, EXPLAIN QUERY PLAN uses index_bookmarks_createdAt for timeline
ordering, with a temporary sort for tied IDs. Domain retrieval uses
index_bookmarks_domain plus an order sort. Keep these existing indexes for this
scope: the measured workload does not justify another composite index yet.
Substring search still scans comparison keys; future indexing is a separate task.

## Comparable application measurements

Run `python tools/benchmark_issue81.py` from the repository root with Python,
adb, Java, and the Android SDK available. The script builds baseline/current
applications with dedicated package suffixes and resets only those guarded test
packages. It never clears the ordinary debug application's data. Fixtures contain
1,000 or 10,000 synthetic bookmarks, about 440 description characters, two entity
occurrences, twenty .invalid domains, no images, and negative icon-cache markers.
Both builds use the same API 36 emulator and debug configuration. The baseline is
main 56d9a35 (v0.5.4); the Paging build is v0.5.5. These measurements use list mode.

Three process-cold starts per dataset/build produce the startup/PSS medians below.
First Paging startup includes migration; later starts do not. Content readiness
and search timing include UIAutomator polling/IPC and are **upper bounds**, not
precise render latency. Search is one sample targeting off-page bookmark 42.
The measurement snapshot precedes the final UI-only domain scroll-restoration fix.
Scrolling is twelve identical swipes, recorded with dumpsys gfxinfo; frame counts
vary. PSS is the operating system's process memory estimate, not retained heap size.
Raw startup samples and scroll percentiles: [results.json](benchmarks/issue81/results.json).

| Build | Rows | TTID median ms | Content-ready upper ms | PSS median KiB | Search upper ms | Janky frames |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Before | 1,000 | 2,135 | 4,648 | 128,243 | 4,016 | 22/224 (9.82%) |
| Paging | 1,000 | 1,966 | 4,658 | 125,336 | 2,744 | 12/237 (5.06%) |
| Before | 10,000 | 1,815 | 4,295 | 162,128 | 4,426 | 24/224 (10.71%) |
| Paging | 10,000 | 1,595 | 4,131 | 125,871 | 2,471 | 22/235 (9.36%) |

The 10,000-row PSS median is about 22% lower (35.4 MiB). Current PSS remains close
between dataset sizes. Search upper bounds and scroll percentiles improve in this
run; the small sample and instrumentation overhead do not establish a general
speedup. Content readiness at 1,000 rows is effectively unchanged. No claim of
uniformly faster startup or physical-device performance is made.

## Verification and limits

- Unit suite: 162 tests; Android SQLite/Paging suite: 7 tests; zero failures/errors/skips.
- Coverage includes supported migrations, raw-data fingerprints, legacy tag parity,
  selected-tag counts, NSFW visibility, write invalidation, bounded retention,
  reverse paging, off-page detail/edit, transaction rollback, read retry, settings
  caller cancellation, and prepend error priority.
- Actual Android UI smoke checks verified a two-column grid, scrolling beyond
  bookmark 250, detail-return anchor preservation, latest-query-only search,
  all-library tag totals (10,000), domain totals (20 domains, 500 each), and
  domain drill-down containing only that domain, domain-list detail return at
  bookmark 260, and replacement with timeline rows after clearing the filter.
  Scripted UI polling uses counted
  labels such as Kotlin (10000), not bare entity names.
- The debug build is installed on the first connected emulator; no ordinary
  debug application data was cleared.
- A fresh independent review found four P2 issues, all addressed with regression
  coverage. The reviewer could not re-review fixes because its usage quota was
  exhausted; final fixes were checked by the implementing agent and test suites.
- Only an API 36 emulator is installed locally. API 34 and physical Pixel runtime
  verification remain unperformed. No AICore-dependent inference was run.
- Synthetic benchmarks exclude images, very large bodies, and real-world tag
  distributions. Measurements are evidence for this environment only.


## PR #85 review corrections

- Added data-preserving v1-to-v2 and v2-to-v3 migrations from historical entity definitions. v1 tables are rebuilt only to relax category nullability and add isAiSkipped=false; original fields/IDs are copied unchanged. v2 adds the import table and indexes. All v1-v6 paths now reach v7. Actual SQLite tests fingerprint original bookmark/trash columns for both earliest versions.
- Domain NSFW changes await initial policy loading, the bookmark update, and the policy-file write in the application scope. Strict writes propagate failures before success publication; existing unrelated background saves retain their previous best-effort behavior. DB/file stores are still separate and do not form a cross-store crash-atomic transaction.
- Detail retry flows start with Loading before success/error. AI-review apply ignores repeated taps until completion and clears its guard in finally.
- Benchmark archive/build subprocesses have 120/900-second timeouts; timeout failures terminate with an explicit message. Wrapper selection uses gradlew.bat on Windows and sh gradlew on POSIX, without requiring executable bits. Three isolated Python tests validate selection and timeout propagation; no full benchmark rerun was needed for these tooling-only changes.
- The Japanese-translation suggestion remains intentionally deferred under the English-default resource policy, as instructed.
- Review verification: 162 unit tests, 9 Android SQLite/Paging tests, and 3 Python tests passed. Debug build succeeds. No AICore inference performed. The temporary review-only version bump was reverted: Issue #81 and all its review revisions use v0.5.5 (code 36).


## Additional UI review corrections

- DomainGrid masks bookmark refresh/prepend/append load states and relies on initialization/domain aggregate state. Retained timeline paging failures cannot show bookmark feedback over domain cards.
- Library and search grids share the existing summary peek overlay. Search supports long-press preview, tap/back dismissal, and hides its header during preview.
- Search saves its last query alongside separate list/grid scroll states. An actual query change requests index zero in both layouts and dismisses an obsolete preview; detail return with the same query preserves position. Requests do not wait for detached layouts.
- Removed the unused updateNsfwByIds DAO method. Entity indexes and the benchmark artifact layout are unchanged, as requested.
- Version policy: one issue, one application version. Reverted the unnecessary review bump to v0.5.5 (code 36).

- Verification for these UI revisions: 163 unit tests and 9 Android tests passed; debug build passed. Synthetic-only UI checks confirmed search long-press peek/back dismissal and replacing a scrolled query (bookmark 34 onward) with a new query starting at bookmark 0. The initial input-replacement probe appended text because the simulated Ctrl+A was ineffective; the validated check used the actual Clear button. No AICore inference was used.
