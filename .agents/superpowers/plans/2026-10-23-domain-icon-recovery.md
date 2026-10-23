# Domain Icon Recovery Implementation Plan

**Goal:** Recover missing domain icons on display, minimize candidate requests, and remove manual batch refresh.

**Approved specification:** Issue #87 and all proposals approved on 2026-10-23. Use article candidates first, root discovery only when article candidates are unusable, then well-known paths and Google. Preferred/minimum/maximum dimensions are 180/96/384px. Adopt the best usable priority-1 image after that tier, otherwise the first usable lower-tier image. Google 128 is unnecessary when 256 yields a usable image. Domain icons have four permits separate from OG previews. Keep domain synchronization and disk preload but remove startup network backfill.

**Architecture:** A pure candidate resolver defines priority and early exits. DomainIconManager owns image decoding, atomic storage, 14-day permanent markers, and a separate instance of the existing shared-fetch coordinator for cancellation and 60-second transient backoff. Icon producers restart on identity/cache generation, not completion notifications. Grid cards track OG completion explicitly and recover icons only after an OG miss.

## Tasks

- [x] Add failing resolver/parser tests; implement tiered discovery and URL deduplication.
- [x] Add manager integration regressions for corrupt cache, sharing, cancellation, clear, marker expiry, and notification/color publication. Route imports and UI through a shared coordinator; preserve the OG pipeline.
- [x] Integrate watermark/domain/list/grid consumers without completion-triggered request cancellation; gate grid icon networking behind OG resolution.
- [x] Remove startup network backfill and manual refresh UI/resources/helpers. Preserve policy sync, disk preload, and import icon hints.
- [x] Verify the full JVM suite, Android image/UI tests, debug builds, and first-device installation. No AICore inference. Request a read-only independent review.
- [x] Update cache/UI/structure docs and task history. Allocate v0.5.7/code38 once for this PR. Commit locally, without pushing until authorized.

## Review focus

- Existing positive caches win over failure markers; corrupt disk files must not count as success.
- A transient failure in discovery or any candidate prevents a 14-day marker on total failure.
- Permanent markers alone determine permanent expiry; no session-level permanent suppression extends their TTL.
- Queue cancellation and clearing cannot recreate files, colors, or markers from old work.
- Cached icons may appear during OG loading; successful OG previews cause zero icon network calls from that grid card.
- List cards pass their page URL; domain-only cards use the root. Existing imports retain apple/favicon hints.
- Settings no longer exposes manual refresh; cache clearing still permits immediate retry.

## Execution ledger

- Latest main baseline tests passed. Dedicated branch: feat/on-demand-domain-icons.
- Ruling: Reuse the existing clean checkout on a dedicated branch, without creating another worktree.
- Ruling: Keep permanent failure persistence in DomainIconManager; the coordinator sees only successful/permanent-result values or transient exceptions, so its OG-specific permanent set is never used for icons.
- Verification: Observed seven failing resolver tests before implementation, then passed them. Reproduced corrupt-disk recovery failure on Android before implementation.
- Review: An independent reviewer identified missing HTML base resolution and missing cold-disk accent notifications. Reproduced both failures, fixed them, and obtained a follow-up read-only review with no remaining blocker.
- Verification: The full JVM suite passes 176 tests with zero failures, errors, or skips. Both debug APK builds pass. The icon manager's eight Android tests and three Compose scenarios cover real encoding, caching, throttling, shared work, negative-cache expiry, cache clearing, OG precedence, and subscription renewal. Existing coordinator cancellation/backoff tests remain in the full JVM suite.
- Final verification: Direct instrumentation passed all 28 Android tests on emulator-5554/API36, including the existing ten database and seven OG image/card tests. The final debug APK is installed with existing data retained. No physical-device, external-network image, or AICore inference test was performed.
