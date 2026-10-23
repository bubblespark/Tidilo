# Bookmark Preview Cache

Grid cards in Library (timeline and domain drill-down) and Search use the following priority:

1. The bookmark's exact OG image.
2. Its cached domain icon.
3. A bundled Material Rounded Link icon (24dp inside a 36dp rounded container).

Missing domain icons now recover through a separate four-worker pipeline after an OG miss; cached icons can display while OG loading is pending. See [Domain Icon Recovery](domain_icon_cache.md).

The placeholder uses the existing domain-accent gradient, with the theme primary color when no domain is available. Initial letters and broken-image indicators are not used. A bookmark without an OG image URL never displays another bookmark's domain-latest OG image.

## Recovery and shared work

`OgImageManager.loadOrFetchOgImage` resolves relative image URLs against the page URL, preserves query parameters, removes fragments, and derives the existing SHA-256 `.webp` filename. It first checks exact memory/disk caches. Missing and undecodable cache files are fetched again. `processAndSaveOgImage` uses this same path during import.

`OgImageFetchCoordinator` shares requests by cache generation and image key, independently of the page's domain. A four-permit semaphore covers network access, image compression, and publication. A queued caller leaving composition cancels its interest; a shared request is cancelled only when its final caller leaves. Actual HTTP work disconnects on cancellation, and the permit is retained until its blocking worker exits.

The transport accepts HTTP/HTTPS, follows at most ten redirects, limits the response to 10MiB, and applies a 10-second timeout. Images retain the existing maximum dimension of 640px and WebP output strictly below 50KiB. Files are published through atomic temporary-file replacement.

## Failures and clearing

- Invalid image data, compression failures, oversized images, and HTTP 4xx responses other than 408/429 are suppressed for the process session.
- Other download/storage failures, including offline errors, timeouts, 408/429, and HTTP 5xx, suppress another attempt for 60 seconds.
- Backoff expiry permits a subsequent request; it does not start an automatic background retry timer.
- Cancellation and obsolete generations do not create failure records.
- Clearing image caches invalidates queued/active shared work and resets failure suppression. Old-generation disk writes, memory publications, and failure records are rejected.
- Cache clearing provides temporary storage relief. Preview images are downloaded again when their bookmarks are displayed; bookmarks and notes remain unchanged.
- Cache invalidation returns `null` to still-active callers; cancellation of the caller itself continues to propagate. Exact-image revision entries are released before publishing the new generation, which recreates card subscriptions. Completion observers retain the displayed bitmap when their domain's memory entry is absent.

Cards recreate image state only when the page/image identity or cache generation changes. Exact-image completion notifications update the bitmap without restarting the active producer. Domain-wide completion notifications remain available for compatibility but do not drive grid image producers.

The database schema and persisted `ogImageUrl` are unchanged. No page metadata is re-scraped and no AI inference or content upload is involved.

## Verification

JVM tests cover the four-worker limit, deduplication, partial/all caller cancellation, queued cancellation, transient failure expiry, permanent suppression, and generation reset. Android integration tests exercise actual bitmap decoding, WebP encoding, disk recovery, import/UI sharing, strict-key isolation, invalid-data suppression, and stale-generation rejection. A Compose card integration test verifies sibling completion does not restart another card's request and captures the Link fallback for visual inspection.
