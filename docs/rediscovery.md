# Rediscovery Engine (Discover data)

This document specifies how Tidilo picks bookmarks for the Discover home: the interest model, shelf rules, reason labels, daily snapshot, and the signals that steer it. It covers the data layer only. The Discover UI ships behind a debug flag in later phases. Everything is computed on the device from Room; nothing leaves the device and no AI model is called.

Code: `data/discover/` (`DiscoverCodes.kt`, `DiscoverRepository.kt`) and `data/discover/engine/` (pure Kotlin, JVM-tested).

## 1. Discover day

- A Discover day starts at **04:00 local wall-clock time** (`discoverDay(now, zone)`), also on DST transition days. Picks stay the same for the whole day.
- "Your map" uses weeks starting on **Monday** (`weekStartDay`).
- Seed: `discoverSeed(day, dealCount) = day * 1000 + dealCount`. A re-deal therefore never reproduces another day's picks.

## 2. Interest model

Computed from `engagement_events` (see [data_models.md](data_models.md) §1.1) plus one implicit save event per bookmark at `createdAt`.

| Signal | Weight |
| :--- | :--- |
| Save, Peek | +1 |
| Detail view | +2 |
| Browser open, first note, collection add | +3 |
| Like | +4 |
| Unlike | −4 |
| Impression, deck flick, snooze, never again | 0 |

- **Decay**: each weight is multiplied by `0.5^(ageDays / 30)`. Events older than 90 days carry no weight; the save always counts.
- **Topic affinity**: summed per topic on three axes (entity skeleton key, subcategory, domain), clamped at 0, and normalized so the top topic of each axis is 1.
- **Item affinity**: `0.5 · max(entity) + 0.3 · subcategory + 0.2 · domain`. Muted topics contribute 0. An item whose every entity is muted is excluded from all shelves.
- **Dormancy**: `clamp(ln(1 + daysSinceLastTouch) / ln(181), 0, 1)`, ×1.2 when never opened. Last touch is `lastInteractedAt`, falling back to `createdAt`.
- **Never opened**: no `DETAIL` or `BROWSER_OPEN` event. Maintenance keeps each bookmark's latest open past 90 days, so this stays true over time.
- **Fatigue**: `0.7^n`, where `n` counts impressions in the last 14 days after the latest open.
- **Rediscovery score**: `affinity × dormancy × fatigue × jitter`, with a deterministic jitter in 0.85–1.15 per (seed, bookmark).

## 3. Shelves

Daily shelves are computed once per Discover day in this order. An item placed on one is not placed again on a later one.

| Shelf | Rule | Size | Reason |
| :--- | :--- | :--- | :--- |
| Today's pick | Library has ≥50 visible items. Top rediscovery score among items with an og:image or a domain (poster). Never Private. Not a hero in the last 30 days | 1 | Never opened: `DORMANT_NEVER_OPENED(days since saved)`; otherwise `AFFINITY_ENTITY(top entity)` |
| On this day | Saved on the same local calendar date 1 year, 6 months, or 1 month ago (1 year falls back to ±1 day). Best score per period | ≤3 | `ON_THIS_DAY(period)` |
| Sleeping gems | Saved ≥30 days ago and never opened. Top by affinity × jitter | 5 | `DORMANT_NEVER_OPENED(days since saved)` |
| Rising | Entities with ≥3 **manual** saves in the last 14 days and a weekly rate at least twice that of the 60 days before. Top 2 by ratio, 6 newest manual items each | ≤2 groups | `RISING(count)` |
| Connections | One pair sharing an entity found on 2–5 items (rarest first), best summed score, pair not shown in the last 30 days | 1 pair | `CONNECTION(entity)` |

Live and weekly shelves:

| Shelf | Rule |
| :--- | :--- |
| Just saved | Manual saves from the last 48 hours, newest first, ≤12 |
| Fresh from RSS | RSS articles with no recorded interest (`lastInteractedAt` is null), newest first, ≤10. Unprotected articles at or past the 10-day unread deadline are left out even before maintenance deletes them. `RSS_EXPIRING(days)` when ≤3 days remain under the 10-day unread rule and the article is not liked or collected |
| Shuffle deck | 30 cards per draw: 24 uniform random + 6 weighted by item affinity, excluding cards flicked in the last 7 days. A new draw on every call |
| Your map | Library has ≥30 visible items. Top 20 unmuted entities by affinity, computed once per week |

## 4. Private content

When Private content is hidden, Private items never enter any shelf, snapshot, deck, or map. A visibility change recomputes today's snapshot and this week's map with the same seed. When visible, Private items may appear everywhere except Today's pick.

## 5. Signals

| Action | Storage | Effect |
| :--- | :--- | :--- |
| Impression (`recordImpression`) | `IMPRESSION` event, at most once per item per Discover day | Fatigue |
| Deck flick (`recordDeckFlick`) | `DECK_FLICK` event | 7-day deck cooldown; topics unchanged |
| Not now (`snooze`) | `SNOOZE` event + suppression until now + 30 days | Hidden from every Discover shelf |
| Don't show again (`neverAgain`) | `NEVER_AGAIN` event + permanent suppression | Hidden from Discover; Library and Search are unaffected |
| Undo (`unsuppress`) | Deletes the suppression | Visible again |
| Mute topic (`muteTopic` / `unmuteTopic`) | `rediscovery_topic_mutes` row | Topic weight 0; map bubble hidden |
| Reset (`resetHistory`) | Clears events, suppressions, mutes, snapshots, maps | Today is recomputed |

None of these change `lastInteractedAt`, so they never extend RSS retention.

## 6. Snapshot storage and concurrency

- `discover_snapshot` stores the daily picks keyed by `(day, shelf, position)`. Rising rows use `position = group * 10 + index` and carry the entity in `topicKey`. `discover_map` stores the weekly map. `discover_snapshot_meta` records each snapshot's `dealCount`, the Private visibility it was computed for, and `computedAt`.
- `ensureToday`, `reDeal`, and `resetHistory` are serialized by one mutex. Each loads its input, computes on `Dispatchers.Default`, and replaces the rows and meta in one transaction. A failure keeps the previous snapshot.
- Reads join `bookmarks`, so Trash and purge take effect immediately. Suppressions, mutes, and Private visibility are also applied at read time, without recomputing.
- Observed flows re-read the clock once a minute: a snapshot collector moves to the new Discover day after 04:00, expired snoozes reappear, the "Just saved" 48-hour window moves, and "Fresh from RSS" expiry labels count down. All of this happens without restarting collection.
- Maintenance keeps 30 days of snapshots and the current and previous map weeks (see [library_maintenance.md](library_maintenance.md)).

## 7. Verification

JVM tests (`app/src/test/.../data/discover/`) cover weights, decay, dormancy, fatigue, jitter, every shelf rule, deduplication, Private handling, determinism, and a 2,000-item performance guard. Instrumentation tests cover the v24 migration, the pruning rule, DAO projections, and `DiscoverRepository` interleavings (concurrent compute and re-deal, Trash, visibility toggles, day rollover, suppressions, mutes, impressions, live shelves, deck, reset).
