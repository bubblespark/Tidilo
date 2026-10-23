# On-demand OG Image Recovery Implementation Plan

**Goal:** Recover missing bookmark preview images without cross-bookmark substitution or unbounded background work.

**Architecture:** Keep image decoding and storage in OgImageManager. Use an internal, Android-independent fetch coordinator for four-worker throttling, reference-counted shared requests, generations, and failure backoff. Grid cards observe cache generations and exact image completion without restarting sibling downloads.

**Approved specification:** Issue #74 and the user-approved discussion on 2026-10-23. Preview priority is exact OG image, domain icon, then Material Link icon; never initials. Transient download failures back off for 60 seconds; permanent HTTP/image failures last for the process session. Clearing the cache resets both.

## Constraints and review focus

- Local disposable cache only; no content upload, database migration, new image assets, or AICore changes.
- Keep 640px / less than 50KiB WebP output and the existing 10MiB / 10-second download limits.
- A cancelled queued caller must not start I/O. One departing caller must not cancel another caller's request.
- Cache clearing must reject old disk writes, bitmaps, and failure records.
- A corrupt disk image is a miss, not a successful import.
- Exact-image completion must reach cards without restarting active requests.
- Cache clearing frees storage temporarily; subsequent displays fetch images again.

## Tasks

- [x] Add deterministic coordinator regression tests for the four-worker cap, shared success, partial/all cancellation, failure expiry, and generation reset. Run RED, then implement the coordinator and run GREEN.
- [x] Add Android image integration tests for strict key isolation, shared import/UI success, corrupt cache recovery, failure reset, and stale-generation rejection. Integrate the injectable downloader, cancellable bounded HTTP transport, atomic writes, and shared fetch API.
- [x] Update BookmarkGridCard to fetch exact images and observe generation/exact completions. Replace the initial-letter placeholder with the Link icon. Update the English cache-clear explanation.
- [x] Run all unit tests, build both APKs, execute image integration tests, and install the debug APK on the first connected device. No AICore inference.
- [x] Update UI/cache documentation and the task tracker, increment v0.5.5/code36 to v0.5.6/code37, review the diff, and commit locally. Do not push without approval.

## Execution notes

- Baseline unit task passed before changes. Latest main is 183e99b (merged PR #85); branch: feat/on-demand-og-images.
- Ruling: Use the existing clean checkout on a dedicated branch; no additional worktree is necessary for this task.
- Ruling: Test actual Android image encoding on-device using existing instrumentation dependencies, avoiding a new Robolectric dependency.
- Verification: Reproduced the four-worker limit failure and the actual cross-bookmark disk fallback before fixes. Corrupt-image recovery also failed before implementing fetch recovery.
- Verification: Initial complete unit suite passed; five Android image tests and the Compose sibling/fallback test passed via direct instrumentation. The Gradle connected runner reported an ADB failure despite a success exit, so direct runner counts are the verification authority.
- Review: An independent reviewer found a last-caller cancellation race in failure publication. The worker now checks cancellation inside the same lock used by last-caller removal, preventing post-cancellation negative-cache publication.
- Final verification: 169 JVM tests passed with zero failures, errors, or skips. Both debug APK builds passed. Direct Android instrumentation passed all 16 tests on emulator-5554 (API 36), including the existing ten database tests, five image tests, and the Compose sibling/fallback test. The final debug APK was installed with existing data retained. No AICore inference or external-network image test was performed.
- Visual verification: Inspected the screenshot captured by the Compose test and confirmed the rounded Link icon on the domain-accent gradient, without initials. Independent follow-up review confirmed the cancellation fix and sibling-completion coverage; no further blocker was found.

## PR #86 review corrections

- Addressed approved comments 2, 3, and 4 only; HTTP worker cancellation structure remains unchanged.
- Retained rendered images on null exact-cache notifications, released exact-image revision entries before publishing a new generation, and returned null to active callers when clearing invalidates a shared fetch. Actual caller cancellation still propagates.
- Reproduced the stale-result JVM failure and all three Android regressions before fixes. Verified 170 JVM tests, seven targeted Android image/card tests, both debug APK builds, and final debug installation on emulator-5554. Independent read-only review found no blocker.
- Incremented the application patch version to v0.5.7 (code 38) per the repository change policy.
