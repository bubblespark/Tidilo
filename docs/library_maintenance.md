# Daily Library Maintenance

Tidilo schedules one unique, network-independent WorkManager request for local midnight. Android may delay execution. RSS fetch times, Wi-Fi-only policy, disabled subscriptions, and Daily fetch OFF do not disable maintenance. Startup ensures the request exists without deleting content. Clock/timezone changes realign future idle requests; overdue, running, and retrying work stays pending. Successful execution targets the next local midnight. All age thresholds use elapsed 24-hour days, not calendar date changes.

## RSS article retention

Only active bookmarks with a non-null `rssFeedId` are eligible for automatic article deletion:

- Without `lastInteractedAt`, delete after **10 days from ingestion** (`createdAt`), regardless of publication date.
- With `lastInteractedAt`, delete after **30 days from that timestamp**. Opening Detail, opening the browser, or previewing the summary renews this interval. Adding a Like also records interest; removing it does not renew the timestamp.
- **Liked bookmarks and bookmarks assigned to any Collection are exempt.** Protection lasts while either condition holds. Removing the last protection resumes the normal age rule without restarting its clock.
- Card display alone does not record interest. Manual bookmarks and RSS bookmarks promoted to manual ownership are not automatically deleted.

Expired articles are permanently deleted without entering Trash, including articles of disabled or deleted subscriptions. Consumed RSS receipts remain, preventing re-import of expired articles. Import tasks and notification history are not deleted by this policy. Existing old articles become eligible on the first maintenance pass after upgrade; there is no additional grace period.

The former per-feed count/unlimited setting is no longer available. Its persisted legacy column remains unused for Room schema compatibility and is never interpreted as a day count.

## OG image cache retention

Images are disposable cache for both manual and RSS bookmarks. The worker deletes a cached image only when **both** conditions hold:

1. At least **7 days** have elapsed since the file was saved, using its last-modified timestamp.
2. Every active bookmark referencing that canonical image has been inactive for at least **7 days**. The reference timestamp is `lastInteractedAt`, falling back to `createdAt` for unread bookmarks.

A recent reference protects a shared image even when the references use different URL spellings or article domains. Files referenced by Trash or unfinished imports remain protected. An orphan found by scanning still requires 7-day file age. Unknown/zero and future file timestamps are retained. Domain fallback copies use the most recent reference across the corresponding domain, including articles without an exact image. Domain icons and temporary files are outside this scan.

Re-downloading an image creates a new file timestamp and a new 7-day protection interval. Displaying a card does not write an image-use timestamp or extend article retention. Liked/collected articles remain stored, but their old images may still be reclaimed and recovered on demand. There is no cache byte-cap policy in this change.

## Article deletion and failure recovery

Room 20 changes the image-cleanup outbox key to `(imageUrl, articleUrl)`. The non-destructive 19-to-20 migration preserves pending jobs. Relative URLs on different domains and shared images on multiple domains retain all cleanup targets. Jobs are grouped by canonical image identity, and matching unreferenced domain copies are removed before the exact file so retries retain their comparison source.

Article deletion and its image-cleanup outbox entry commit together. After commit, maintenance immediately removes the associated image if no active bookmark, Trash record, or unfinished task references it; this removal does not wait seven days. Shared images remain subject to the normal image-age rule. Matching domain fallback copies are removed only when no domain references remain.

RSS expiration runs in bounded transactions of at most 100 rows. Cache scanning takes narrow reference projections in batches of at most 64 candidate files. Each reference check and removal shares a Room write transaction with engagement, promotion, and Collection edits. OgImageManager serializes file removal with downloads/cache clearing, checks the selected file timestamp again, invalidates memory/color caches, and prevents old in-flight work from republishing removed images.

Cancellation propagates. File removal failures remain recoverable through the durable outbox or a later age scan, and the worker retries without postponing its pending daily boundary. Attempts are idempotent; a retry is not a second scheduled daily cycle. Cleanup itself does not make network requests.

## Implementation and verification

`data/maintenance/RetentionPolicy.kt` defines age predicates. `LibraryMaintenanceRepository` runs article expiration, image outbox draining, and image aging in order. `LibraryMaintenanceScheduler` and `LibraryMaintenanceWorker` own scheduling and retries. The worker class is retained for WorkManager's persisted class-name lookup.

JVM policy tests cover exact boundaries, repeated interest, and future timestamps. Android tests exercise real Room/files, Like/Collection protection, deletion rollback, shared references, Trash/task protection, failed removal retry, stale-file snapshots, in-flight download rejection/recovery, unique work, timezone changes, overdue work, and cancellation. Live AICore inference is not required.
