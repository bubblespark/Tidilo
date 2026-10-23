# RSS and Atom Subscriptions

Settings > Data Management is ordered Summary, RSS, Clear Image Cache. RSS (`settings_rss`) manages RSS 2, Atom, and RDF subscriptions. All subscription state, receipts, article content, and AI output remain on the device. Feed acquisition requests the publisher's XML and, when necessary, page metadata; it does not send bookmark content to an AI service.

## Settings and acquisition

- Daily fetch time defaults to **00:00 local time**. The Material 3 time picker changes the shared schedule. Execution is approximate and can be delayed by Android power management.
- **Fetch only over Wi-Fi** appears immediately below the time control and defaults ON. Both scheduled acquisition and Fetch now, including their OGP requests, require validated Wi-Fi transport. OFF permits other validated Internet networks.
- Requests bind to the selected network. Losing Wi-Fi cannot transfer a request to cellular. Waiting work remains pending without creating a separate job for every missed day. The screen shows Waiting for Wi-Fi while the policy requires an unavailable connection.
- WorkManager owns unique daily periodic work and unique manual work. Startup, preference edits, clock/timezone changes, and successful execution correct scheduling. Updating Wi-Fi policy also updates a pending manual request's constraints without losing its original request time.
- ETag and Last-Modified validators support 304 responses. Individual feed failures do not prevent other feeds from importing. Transient failures retry with WorkManager backoff; feeds already successful for that request are skipped on retry.
- Each response is limited to 2 MB and a 30-second monotonic deadline, with individual connect/read timeouts of at most 10 seconds and at most six connection attempts including redirects. Direct enrichment uses windows of 20 entries and at most four simultaneous requests. These bounds do not impose an article-count ingestion limit.
- Feed cards show fetched title, URL, Import Mode, Max saved items, an Enabled checkbox, and errors. Attempt/success timestamps are internal only. A successful acquisition clears the previous error. Disabled feeds are excluded from scheduled and manual acquisition while saved articles and pending AI tasks remain. Fetch now provides an explicit refresh/retry. Registration validates a real supported XML document, not an HTML page or an arbitrary URL.

## Parsing and images

`RssFeedParser` uses the existing Jsoup XML parser. It handles namespaces, CDATA/escaped HTML, RSS GUIDs, Atom IDs and HTML alternate links, relative URLs, `xml:base`, and publication dates. Only HTTP(S) article/image URLs without embedded credentials are accepted. URL normalization preserves query strings and removes fragments. Missing titles fall back to the URL/host; missing publication dates use first-seen time for selection and retention. Atom updated time is not treated as publication time.

Title, description, and the first 800 characters of body text come from XML. RSS tasks never use article Readability extraction, including when their body snippet is empty or the app restarts. XML media/enclosures and inline images take precedence. Only a missing image triggers a metadata-only article request for `og:image`. Ordinary image-metadata failure preserves the valid feed text; losing a required Wi-Fi network leaves acquisition retryable.

## Modes, queue, and inference

New feeds default to **Direct**, with a retention count of **30**.

- **Direct** saves every valid unseen entry supplied by the fetched feed. It bypasses Ready tasks and does not invoke AICore. It has no ingestion-count cap.
- **AI Analyze** keeps the newest **five unstarted tasks per feed**, counting READY and WAITING_AI together. Existing unstarted tasks and new entries compete by publication time, then first-seen time and stable URL/ID ties. PROCESSING_AI and ERROR are outside that cap. Dropped older entries are recorded as consumed.
- Import displays Ready, Ready - RSS, and Error separately. Each Ready section has independent expansion and five-item preview/More controls, with the existing swipe and Undo actions. RSS errors retain feed provenance.
- The foreground import worker selects waiting manual tasks before RSS tasks at every iteration. An already active inference finishes normally. Scheduled workers only acquire and persist data; they never invoke AICore. Foreground auto-run settings continue to govern automatic promotion and inference.
- Inference starts only after an atomic SQL claim checks the task and its subscription. Removed or no-longer-AI subscriptions move invalid retry waiters back to Error, avoiding a repeated selection loop. Cancellation, quota rollback, and Undo reconcile the unstarted-task cap. AI success and fallback preserve RSS ownership, publication date, and first-seen time.
- Maximum AI queue is shown in small text and equals five times the number of enabled AI feeds. A warning above 20 possible tasks is a heuristic, not an SDK quota or remaining-capacity estimate. Running tasks, errors, retries, manual imports, and debug usage are separate.

## Persistence, duplicates, and lifecycle

Room schema **9** adds an enabled flag through `MIGRATION_8_9` (existing subscriptions remain enabled and retain their configured counts). The original RSS schema, through `MIGRATION_7_8`, adds `rss_feeds`, `rss_seen_items`, and `rss_image_cleanup`. Nullable `rssFeedId` and `rssPublishedAt` fields on bookmarks, trash, and import tasks preserve existing manual rows and source identity. Indexed `ogImageKey` fields are backfilled from existing image URLs for targeted reference checks. Search-derived columns and normalized tag occurrences remain maintained by the existing DAO transactions.

Receipts use feed identity plus GUID/Atom ID, with normalized URL fallback and a URL lookup. Receipt insertion, duplicate checks, content/queue changes, and retention selection commit transactionally. A failed transaction does not consume an entry. RSS ingestion skips URLs already present in the library, trash, or import queue, preserving the existing record. Retention deletion and queue replacement do not cause receipt replay. The reverse case, manually saving a URL already acquired by RSS, intentionally retains existing behavior and belongs to a later issue.

Changing mode applies to future acquisition. Switching AI to Direct offers saving or discarding existing unstarted tasks; active work and errors are separate. Editing the URL registers a validated replacement before removing the previous subscription. Deleting a subscription stops future acquisition and removes unstarted tasks while preserving saved RSS articles, error records, and source identity. Revision checks reject stale fetch results after edits or deletion; saved articles never become manual merely because their subscription was removed.

## Retention and cache cleanup

Unlimited disables automatic count deletion. A count from **10 to 500** keeps that many active RSS-owned articles for each feed. It is independent of acquisition and queue limits and is not an age measured in days. Retention runs after Direct/AI/fallback saves, pending-task conversion, and reducing a configured count. The oldest publication date, with first-seen fallback, is removed first. Automatic deletion is permanent and bypasses Trash; manual bookmarks and running tasks are protected.

Trash mappings and targeted AI-review edits preserve source fields. A future restore through the bookmark-save path is subject to the source feed's limit again; issue #91 does not add a new trash-restoration interface.

Deletion records a durable image-cleanup outbox after selecting excess bookmarks. Cleanup checks indexed canonical image identities across active bookmarks, trash, and unfinished tasks. Shared relative/absolute image aliases stay protected. Only unreferenced exact cache files are deleted; domain icons are preserved. A domain-latest image is removed only when there are no other domain references and its bytes match the deleted exact image. Per-image epochs reject publication by older in-flight loads/downloads and invalidate memory entries. Failed deletions remain queued for a later cleanup pass.

## Browsing

Library's Newest First and Domain modes show manual bookmarks only. RSS mode shows RSS-owned bookmarks, including those from deleted subscriptions, using the existing newest-first list/grid presentation. Source changes clear a selected domain and reset the relevant scroll position. Search and Tag Selection include both sources with the existing NSFW rules. Summary counts include both sources and exclude trash from the active total.

## Verification boundaries

JVM fixtures cover formats, metadata, HTTP validators/failure handling, stable selection, scheduling/DST, source mapping, manual queue priority, empty RSS bodies, first-seen fallback, and subscription-removal races. Android tests exercise real Room upgrade paths, source queries, receipt rollback/replay, concurrent ingestion, retention, task claims, Undo, canonical image references, Wi-Fi constraints, in-flight image deletion, and labeled Settings controls. Network and AI work in automated tests use fixtures or fakes. Actual AICore inference and real mobile/Wi-Fi handover require a physical supported device; the emulator does not provide AICore.

The feed editor groups Direct/AI Analyze under Import Mode and Unlimited/retained count under Max saved items. New feeds default to30; editing existing subscriptions preserves their settings. Fetch now uses a filled Material button.
