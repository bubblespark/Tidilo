# RSS Feed Ingestion Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task by task after user approval. This is a proposal, not an approved implementation specification.

**Goal:** Implement issue #91: RSS/Atom ingestion, daily fetching, per-feed retention, manual-priority AI queues, and an RSS-only Library mode with combined search/tag retrieval.

**Architecture:** Persist subscriptions, source metadata, and deduplication receipts in Room. A feed fetch/parser and transactional ingestion coordinator feed either direct bookmark writes or existing foreground AI processing. WorkManager performs network ingestion only. Existing paged SQL queries receive an explicit source scope.

**Tech Stack:** Kotlin, Compose Material 3 TimePicker, Room, existing Jsoup XML parser, AndroidX WorkManager (stable compatible version verified at implementation time). No additional XML parser dependency.

**Spec:** https://github.com/bubblespark/Tidilo/issues/91 plus the proposed decisions below. User approval of these decisions is pending.

## Global Constraints

- English UI/resources and English code/docs; user communication in Japanese.
- Feed content, receipts, and inference remain local; network calls only retrieve feeds/article metadata/images.
- AICore runs only through the existing foreground processing worker, never in the scheduled fetch worker.
- Preserve legacy/manual records and user edits. No arbitrary global task/feed cap.
- Keep Paging and SQL aggregates; never load the complete bookmark library for filtering or retention.
- Allocate the next patch once per PR during implementation: expected v0.5.9/code40 from current main v0.5.8/code39; recheck main first.

## Current Findings

- Room schema version is 7. Bookmarks/import tasks/trash have no RSS ownership metadata.
- ImportRepository.importUrl performs full-page extraction before inserting READY tasks; RSS must use a separate preparsed insertion path.
- GeminiTaggingService currently selects its fast path using nonblank bodySnippet. RSS needs explicit preparsed-input identity even when feed text is empty.
- BookmarkRepository.addBookmarkAndAwait deletes matching IDs/URLs before insertion. RSS cannot reuse this policy without a transactionally checked source-aware conflict policy.
- ImportTaskDao orders pending work by createdAt descending; manual priority must be enforced when selecting the next inference, not only when promoting a batch.
- Library pages, counts, domain aggregation, search, and tag counts currently share source-agnostic data. Library gets MANUAL/RSS scope; search/tags retain ALL scope and existing NSFW rules.
- OG cache identity is based on image URL, with separate domain-latest copies. Deleting one RSS bookmark cannot indiscriminately delete shared cached images.

## Proposed Decisions Requiring Approval

1. Daily time means local-time approximate scheduling, not an exact alarm. Use unique periodic work with network connectivity, initial alignment, and next-time correction after execution/timezone/time/DST/settings changes. Show last attempt, last success, and per-feed error; add Fetch now. Retry transient failures with backoff, isolate failures per feed, and avoid replaying successful feeds on retry.
2. Count READY plus WAITING_AI RSS tasks as the five unstarted tasks per feed. Replace only the oldest unstarted RSS work; never replace PROCESSING_AI or ERROR tasks. Reconcile the cap when cancellation/quota handling returns waiting work to READY. An already-running RSS inference finishes; manual waiting work runs before the next RSS attempt. The queue indicator is five times active AI subscriptions and excludes running/error tasks.
3. Use durable feed/item receipts (GUID or Atom ID plus normalized URL fallback), not timestamp-only cursors. Mark intentionally rolled-out/retention-deleted items consumed; malformed/failed-to-persist items remain retryable. Serialize fetch/edit/delete per feed and commit receipts, queue changes, bookmark writes, and retention selection transactionally. Check library, queue, and trash for cross-source duplicates; preserve query strings and avoid aggressive URL merging.
4. AI Analyze queues at most the newest five unstarted entries per feed. Direct bypasses Ready tasks and imports all valid unseen entries supplied by the feed without an ingestion-count limit. Unlimited or a configured item count controls automatic deletion only, independently of ingestion or queue limits. Use publication time with first-seen time fallback and stable tie-breaking; do not treat Atom updated time as a new publication. Sort existing unstarted tasks and new entries together. Discarded older entries are recorded as consumed.
5. Retention permanently deletes only active RSS-owned bookmarks for that feed, bypassing Trash; order by source publication time with first-seen fallback. Apply after each save and when reducing a limit. Never delete manual records or a currently running task. Preserve RSS ownership/publication metadata through trash/restore and AI review. A restored RSS bookmark is subject to its feed limit again, with that consequence disclosed.
6. Retention cache cleanup runs after DB commit and deletes only image keys no longer referenced by active bookmarks, trash, or tasks. Preserve shared domain icons and unrelated latest copies; remove a domain-latest copy only when ownership can be verified. Coordinate with in-flight recovery and memory-cache invalidation; failed cleanup remains retryable.
7. Feed deletion stops fetching, removes unstarted RSS tasks, and preserves saved RSS bookmarks by default. Retain source identity/display metadata so they do not leak into manual Library. Recheck removed feeds before committing an in-flight result. Changing mode affects future ingestion; offer explicit handling of existing unstarted tasks when switching to Direct. Treat a feed URL change as replacement registration so receipts do not cross unrelated feeds.
8. User clarification: RSS ingestion skips a URL already registered manually or via RSS, without overwriting or promoting the existing record. The reverse case, manually importing a URL already saved via RSS, retains current manual-import behavior and is deferred to a later issue. Do not add manual ownership promotion or a Keep in Library action in issue91.
9. Set a clearly labeled heuristic queue warning above 20 possible RSS attempts (provisional product default, not an SDK quota). Historical quota detections may be shown as context, never as a remaining-capacity calculation. Explain that five tasks per feed does not bound retries, running tasks, or manual/debug usage. Default new feeds to Direct, retention100; use the user-proposed default daily time00:00 local, with AI Analyze explicitly selectable.
10. Registration validates an actual RSS2/Atom/RDF document and HTTP(S) item/image URLs; use redirects/xml:base/relative resolution. Feed title falls back to host when absent. Fetch fully on first registration, then use ETag/Last-Modified. Limit network response size, duration, and concurrency without silently dropping queued work. Image/OGP failure does not discard valid feed text. Preserve domain NSFW policy and global bookmarkability rules. Feed requests do not include app content.

## Tasks and Verification

### 1. Persist subscriptions and source metadata

- [ ] Add RssFeedEntity/Dao, durable RssSeenItemEntity/Dao, and necessary source/publication fields in BookmarkItem, BookmarkEntity, ImportTask/Entity, and TrashedBookmarkEntity mappings.
- [ ] Store schedule preferences separately from feed/item receipts; retain source metadata for deleted subscriptions without cascading saved content.
- [ ] Add migration7To8 and relevant indexes; register the migration and verify existing upgrade paths preserve user records.
- [ ] Tests first: legacy migration fingerprints, source round trips, source-preserving edits/trash/restore, receipt uniqueness and transaction rollback.

### 2. Parse and fetch feed metadata

- [ ] Add parser/RssFeedParser.kt with normalized feed/entry models. Parse RSS2, Atom alternate links, RDF items, namespaced media, CDATA/escaped HTML, and publication dates.
- [ ] Add data/rss/RssFeedFetcher.kt for bounded IO, HTTP validators, registration validation, and isolated feed errors.
- [ ] Reuse/extract a metadata-only OGP reader; never invoke Readability for RSS. Inline XML image wins over article OGP; resolve relative and xml:base URLs against the final response URL.
- [ ] Fixture tests first: each format, namespaces, invalid/absent dates, reordered/backdated entries, no text, invalid URLs/XML, duplicate IDs/URLs, HTML text/images, 304 and transient failures.

### 3. Ingest and retain transactionally

- [ ] Add data/rss/RssIngestionRepository.kt to coordinate receipts, source-aware duplicate checks, Direct writes, newest-five queue replacement, and retention.
- [ ] Add targeted SQL queries for existing URLs/IDs, unstarted tasks, and oldest feed-owned bookmarks. Avoid full-library collections.
- [ ] Add reference-aware, in-flight-safe per-image cleanup through OgImageManager rather than clear-all helpers.
- [ ] Tests first: concurrent fetches, commit failure/retry, stable top-five selection, late articles, purged-item replay, shared URLs/images, RSS duplicate skipping without manual-flow changes, limit changes, deletion during fetch/save, trash restoration.

### 4. Integrate foreground AI processing

- [ ] Add an explicit RSS preparsed import path and propagate source metadata through success, Direct fallback, retry, cancellation, undo, and AI-review updates.
- [ ] Select manual work before RSS at every worker iteration; do not preempt an active inference. Reuse existing session ownership, foreground gating, quota handling, and usage recording.
- [ ] Split Ready and Ready - RSS in ImportViewModel/ImportScreen with independent expansion and More Items state; reuse swipe/undo actions. Show feed provenance on RSS errors.
- [ ] Tests first: manual precedence, foreground gating, no RSS Readability fallback for empty snippets, cancellation/undo cap reconciliation, quota return, retry outcomes and metadata retention.

### 5. Schedule background fetching

- [ ] Add WorkManager dependency, data/rss/RssFetchWorker.kt, and RssScheduler.kt. Use unique scheduled/manual work and prevent overlapping ingestion of the same feed.
- [ ] Reschedule when local clock/timezone/preferences change; cancel scheduled work when no active subscriptions remain. Persist success/error state and expose observed scheduling status.
- [ ] Tests first: next-run boundaries and DST, changed schedule, duplicate wakeups, network constraints, partial feed failures, process restart, removed subscriptions, and no background AICore calls.

### 6. Settings and source-scoped browsing

- [ ] Add RssSettingsScreen/ViewModel, feed add/edit/delete UI, Material3 TimePicker, import mode/retention controls, maximum queue indicator and heuristic warning, statuses/retry/Fetch now.
- [ ] Add Screen.RssSettings and Settings entry using the existing circular icon style; English resources only.
- [ ] Extend LibraryFilterMode persistence and controls with RSS; add explicit source scope to pages, counts and domain queries. Reset incompatible domain selection when changing scope.
- [ ] Keep Search and Tag Selection scoped to ALL, respecting NSFW visibility. Keep Summary total counts inclusive of both sources.
- [ ] SQL/UI tests first: manual/RSS lists and counts, domain drill-down, combined search/tags, filter persistence, empty/error/loading states, navigation, schedule picker, large font and accessibility.

### 7. Final verification and documentation

- [ ] Run all JVM tests before every commit. Build debug/test APKs; run migration/instrumented tests with controlled feed fixtures and fake AI results. Install debug on first connected device without clearing its data.
- [ ] Review shared-file cleanup, concurrent state transitions, data preservation, background/foreground separation, and retention semantics independently.
- [ ] Update docs/overview.md, data_models.md, ui_design.md, aicore.md, tech_stack.md, structure.md, application_summary.md as relevant, plus .agents task history.
- [ ] Commit locally; push/PR only after user approval. Keep the one-version-increment-per-PR rule.

## Review Focus

- A feed result arriving after a manual save, feed deletion, or mode change must not overwrite newer decisions; reverse-direction manual-import deduplication is explicitly deferred.
- Queue rollback, quota handling, retries and undo must not bypass the per-feed unstarted-task cap.
- Missing XML body text must not accidentally trigger article Readability scraping.
- Retention and image cleanup must preserve manually owned content and shared/in-flight image references.
- Local daily scheduling is best effort; failures and last successful refresh must remain visible without implying exact execution.

## User Clarifications (2026-10-24)

- Queue capacity and retention are independent. AI Analyze has five unstarted tasks per feed; Direct has no Ready task or ingestion-count cap. Count-based retention is automatic deletion, not a Direct import limit or day-based expiry.
- Skip RSS ingestion of URLs already registered manually or through RSS. Do not alter the reverse manual-after-RSS case in this issue; its resolution belongs to a later issue.
- Change the proposed default daily time to00:00 local. Midnight does not guarantee Wi-Fi or charging; network restrictions beyond connectivity remain a separate, unapproved product decision.
- Other proposals remain subject to user approval. No application implementation has started.
