# Domain Icon Recovery

Domain icons recover when visible watermark or card consumers need them. Startup only synchronizes domain policies and preloads cached files; it does not fetch all missing domains or scan their article URLs. Imports retain their explicit apple-touch/favicon hints and share the same fetch pipeline as visible cards. Manual single-domain and batch re-fetch actions are removed.

## Candidate selection

- An explicit apple-touch URL is evaluated first. If it provides at least 180px in both dimensions, HTML discovery is unnecessary.
- Discover article HTML and evaluate high-priority candidates first: apple-touch icons, Windows TileImage metadata, and declared icon sizes of at least 180x180. Relative URLs honor redirects and HTML base declarations.
- Immediately accept a decoded image of at least 180x180. Otherwise, after exhausting this tier, accept its largest usable image of at least 96x96.
- If that tier has no usable image, accept the first usable standard HTML/favicon candidate. Declaration sizes order candidates; decoded dimensions determine acceptance.
- Only when all article candidates are unusable, discover root HTML once and apply the same tier rules.
- Then try root apple-touch paths, Google Favicon 256, and finally Google 128. A usable 96–179px result from Google 256 is accepted without requesting 128.
- Deduplicate resolved URLs across all stages, preserving query parameters and removing fragments. Restrict candidates to HTTP(S).

## Work, storage, and failures

`DomainIconManager.loadOrFetchIcon` checks normalized-domain memory and disk caches before networking. Corrupt disk images are misses. `fetchAndSaveDomainIcon` shares the same generation/domain coordinator with imports. Its four permits are independent of the OG preview coordinator's four permits. A queued request starts no network work after cancellation, and a shared request is cancelled only after its last caller leaves. Blocking requests run on interruptible IO workers and retain their permits until they exit; existing per-request timeouts remain four seconds.

Valid icons are downsampled to at most 384px, processed with the existing transparency rules, and saved as PNG through temporary-file atomic replacement. Bitmap and dominant color caches are populated before publishing a domain update. Cold disk loads also publish a color update, so grid accents do not retain their initial generated color. Existing legacy `filesDir/domain_icons` cache migration remains supported.

The existing `.none` marker is the sole permanent-failure cache and expires after 14 days. A total failure writes it only when a server was contacted and no discovery/download stage had a transient error. Offline errors, timeouts, HTTP 408/429/5xx, and storage failures use a 60-second in-memory backoff instead. Backoff expiry enables a subsequent request; it does not start a retry timer. The OG coordinator's session-level permanent suppression is not used for domain icons.

Clearing icon caches cancels shared work, advances the generation, and clears bitmaps, colors, markers, and transient backoff. Old work cannot republish an image or failure marker. Cache invalidation returns a miss to active callers; actual caller cancellation propagates.

## UI behavior

`rememberDomainIcon` owns the icon producer by identity and cache generation, while completion notifications only update its bitmap. Multiple visible consumers share a fetch without cancelling each other on completion. Watermarks pass their article URL when available, and domain cards use the root.

Grid cards distinguish pending OG resolution from a completed miss. During OG loading they may display a cached domain icon, but perform disk-only icon lookup. They start icon network recovery only when the OG URL is absent or OG resolution completes without an image. An OG success starts no icon network request from that grid card. The final Link placeholder is unchanged.

The clear-cache explanation states that both preview images and domain icons are downloaded again when needed. The Re-fetch Domain Icons setting and its dialogs/resources no longer exist. No bookmark schema or AI inference behavior changes.
