# Issue #81: Paging migration work log

## Status

Implementation is in progress on `mod/room-backed-paging`, based on main
`56d9a35`. The application still uses eager bookmark collections. No database
migration or Paging presentation switch has been applied yet.

## Search compatibility checkpoint

`BookmarkSearchKey` uses simple Unicode uppercase-then-lowercase mapping by
code point, without multi-character expansion, normalization, or tokenization.
Its tests compare the resulting substring matching with the current Kotlin
ignoreCase behavior, including Japanese, Turkish I variants, Greek sigma,
Kelvin sign, sharp S, full-width Latin, combining marks, supplementary letters,
and literal SQL wildcard characters. Case mappings for every Unicode scalar
value are also compared against JVM ignoreCase equality.

These tests establish comparison-key feasibility, not complete SQL search parity.
The key has not yet been connected to persistence or production queries.

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
3. Implement the v7 migration, synchronized writes, DAO queries, and Paging.
4. Replace full-list repository, settings, details, and UI consumers.
5. Verify migration, failure handling, page retention, scrolling state, and
   before/after performance; document results and update the application version.

Do not treat this checkpoint as completion of Issue #81 or as a performance claim.
