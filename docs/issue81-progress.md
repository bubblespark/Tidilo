# Issue #81: Paging migration work log

## Status

Implementation is in progress on `mod/room-backed-paging`, based on main
`56d9a35`. DB v7, synchronized derived writes, query factories, and Repository
Paging APIs are implemented. The application version is 0.5.5 (code 36).
The existing screens and their eager bookmark observer have not yet been
switched over; Issue #81 is not complete.

## Search compatibility checkpoint

`BookmarkSearchKey` uses simple Unicode uppercase-then-lowercase mapping by
code point, without multi-character expansion, normalization, or tokenization.
Its tests compare the resulting substring matching with the current Kotlin
ignoreCase behavior, including Japanese, Turkish I variants, Greek sigma,
Kelvin sign, sharp S, full-width Latin, combining marks, supplementary letters,
and literal SQL wildcard characters. Case mappings for every Unicode scalar
value are also compared against JVM ignoreCase equality.

These tests establish comparison-key feasibility, not complete SQL search parity.
The key is now maintained by DAO writes and the v6-to-v7 migration, and is
used by the new parameterized SQL query factories.

An in-memory SQL probe on the connected API 36 `sdk_gphone64_x86_64` emulator
reported SQLite 3.44.3. LIKE matched accented Latin case and sigma variants, but
did not match either dotted or dotless I against ASCII i, unlike the current
Kotlin search. Therefore using the device's LIKE behavior directly is insufficient.
The probe also confirmed JSON array operations and literal `instr` substring
matching on that environment. This is not evidence for every supported API level;
Room integration tests on supported environments remain necessary.

## Planned persistence representation

- Store nullable normalized domain on active bookmarks only.
- Store comparison keys for ordinary search fields and category/subcategory IDs.
- Normalize entity occurrences into a child table keyed by bookmark ID and
  occurrence position, retaining original values and comparison keys. Do not
  deduplicate repeated entities when counting occurrences.
- Maintain derived values and entity rows in the same transaction as bookmark
  inserts, replacements, tag edits, deletions, and trash restoration.
- Backfill all derived data during the v6-to-v7 migration using bounded,
  stable-ID batches. Do not expose partially backfilled results.
- Bind query values and use literal substring comparisons over comparison keys;
  map category/subcategory label matches to their existing IDs.

## Remaining work

1. Record application startup, content readiness, search, scrolling, and memory
   baselines for isolated 1,000- and 10,000-bookmark fixtures.
2. Finalize query and aggregate parity against actual SQLite, including combined
   selected tags, NSFW filtering, original-case count buckets, and empty queries.
3. Validate additional query combinations and query plans on representative data;
   validate the minimum supported Android environment in addition to API 36.
4. Replace full-list repository, settings, details, and UI consumers with the new
   Paging and aggregate APIs; complete targeted writes and transactional trash moves.
5. Verify migration, failure handling, page retention, scrolling state, and
   before/after performance; document results and update the application version.

Do not treat this checkpoint as completion of Issue #81 or as a performance claim.

## DB foundation verification

- Unit tests: 159 passed, zero failures/errors/skips.
- Android SQLite integration tests: 2 passed on the API 36 emulator. One test
  exercises search parity, deterministic ordering, NSFW filtering, tag occurrence
  counts, insert/update/delete synchronization, and rollback. The other covers
  upgrade paths from v3, v4, v5, and v6, with 1,000- and 10,000-row v6 fixtures.
- Original columns across bookmarks, trash, and import tasks are compared using
  bounded-memory SHA-256 fingerprints before and after migration. Room validates
  the resulting v7 schema; unknown upgrade paths fail instead of deleting data.
- First measured v6 upgrade/open-and-count time: 172 ms for 1,000 bookmarks and
  1,419 ms for 10,000 bookmarks on the connected API 36 emulator. These synthetic
  fixtures have two entity occurrences per bookmark, short descriptions, and
  twenty domains. Timing includes opening/validation and the first count; it is
  not a standalone backfill timer, an application startup baseline, or a physical
  device performance prediction. Larger bodies/entity arrays require further checks.
- Startup icon preparation now queries distinct domains and walks URL batches of
  64, retaining at most two batches for last-candidate detection. Existing fallback
  and transient-failure suppression of negative markers are preserved by the loop.
- Paging retention defaults: page size 30, prefetch distance 10, initial size 60,
  max size 150, no placeholders. UI retention and scrolling validation is pending.

The instrumentation fixtures use dedicated, randomly named databases, not the
user's library. Full application before/after measurements remain outstanding.
