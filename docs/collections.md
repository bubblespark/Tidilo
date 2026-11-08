# Manual Collections

Collections are user-curated groups, independent of AI taxonomy, bookmark origin, and Like. An active bookmark may belong to several collections, including collections containing both manual and RSS bookmarks. All collection content stays in the local Room database.

## Persistence and lifecycle

Room version 16 adds `collections` and `bookmark_collection_cross_ref` through the non-destructive `MIGRATION_15_16`. Existing bookmark, trash, RSS, queue, notification, and engagement data is preserved.

`CollectionEntity` stores UUID String `id`, `title`, `description`, nullable `presetIconId` and `customSymbol`, `colorIndex`, `createdAt`, and `updatedAt`. The membership table has a composite `(bookmarkId, collectionId)` primary key, individual indices on both columns, and cascading foreign keys. Bookmark IDs remain Strings, matching existing bookmark identity.

CollectionDao returns aggregate counts through Flow and collection bookmarks through PagingSource. CollectionRepository validates writes and saves membership sets atomically. Empty collections remain visible. Counts and pages apply the same NSFW visibility rule and include all bookmark sources. Collections and their bookmarks sort by creation time descending, then ID descending. Membership changes do not change collection creation order.

Collection membership exempts RSS articles from automatic age deletion while any membership remains. Removing the last membership resumes the normal age rule without restarting its clock; a Like still protects the article. Image cache remains disposable under [Daily Library Maintenance](library_maintenance.md).

Normal bookmark upserts preserve memberships. Explicit URL replacement transfers the affected memberships to the surviving bookmark ID within the existing write transaction. Failed replacement or membership writes roll back completely. A collection deleted while being edited cannot be recreated by Save.

Deleting a collection removes its memberships and keeps every bookmark. Moving a bookmark to trash removes all memberships and stores `isLiked = false` in its trash snapshot. Unrelated engagement counters and timestamps remain intact. Collection or Like restoration is outside this feature.

## Library and editing

The Library filter menu includes Collection. Its single-column list uses the `prototype/collection-library` design: two background card layers, bold ellipsized heading, two description lines, count pill, gradient, and unframed icon/symbol watermark. Empty collections still have cards. The footer's dashed add card opens New Collection. Long press gives haptic feedback and opens Edit Collection. Tap opens the collection's paged bookmark list, with the existing list/grid switch and back behavior.

Collection cards and the add card expose button roles to accessibility services. Count pills use an opaque accent-tinted surface with `onSurface` content for legibility across all eight accents in light, dark, and dynamic themes. The list subtitle uses quantity-aware singular/plural collection counts. Card gradients and dashed-border drawing objects are cached.

Card bookmark counts also use English singular/plural quantities and retain the existing Japanese count format. The Collection filter exposes its button role and selected state. Preset icons and palette colors form separate single-choice groups with radio roles; a custom symbol leaves the preset group unselected.

The selected collection ID is saved separately from its latest Room metadata. Editing a title or color updates presentation without changing identity. Collection ID and NSFW visibility participate in the Paging session key; outgoing sessions retain their existing leases, and same-key database invalidation keeps loaded bookmarks mounted. Deleting the selected collection returns to the collection list.

The editor uses a scrollable Material 3 modal bottom sheet with IME and navigation-bar padding. A fixed header places Save to the right of the title (Create for a new collection), keeping it reachable while the form scrolls. There is no sheet-level Cancel button; back, scrim, or swipe dismissal abandons the draft. Draft fields and delete confirmation survive state restoration. All editing controls wait for the initial row to load, so early decoration input cannot replace existing fields. Save is guarded while pending, failures retain the draft, and confirmed deletion leaves bookmarks intact. Sheet observers are scoped to the sheet lifetime.

Editor load failures show a load-specific message and Retry. Retrying restarts the row observer, preserves any restored draft, and keeps editing and writes disabled until the read succeeds. Repeated retry requests cannot start overlapping observers.

Titles are trimmed, required, and limited to 40 extended grapheme clusters. Descriptions are optional, limited to 100 clusters and two logical lines; additional line breaks become spaces. Android ICU BreakIterator preserves compound emoji, flags, skin tones, and combining marks. Custom symbol input keeps the last trimmed grapheme cluster and overrides the preset icon while present. Duplicate titles are allowed.

Preset IDs are `folder`, `schedule`, `star`, `work`, `code`, `lightbulb`, `palette`, and `favorite`. The theme palette has eight colors in fixed index order: cyan, sky blue, indigo, lavender, rose, coral, amber, and green. Default preset is `folder`, default color index is 0; repository writes reject unknown presets and indices outside 0 through 7.

## Bookmark assignment

The detail toolbar's Collection star opens Choose collections. At least one saved membership fills the star with a yellow/gold accent selected for at least 3:1 contrast against the toolbar background; no memberships use the outline icon. Room membership changes update this state reactively, including deletion of the final collection. The selection sheet has neither Save nor Cancel. Checkbox changes remain a draft until a close request (back, scrim, swipe, or accessibility dismissal) atomically replaces that bookmark's memberships. After a successful save, the sheet runs its standard slide-down hide animation before the parent removes it or navigates. Failure keeps the sheet and selections visible for another close attempt or Retry. Closing during initial loading waits for the stored membership to load before saving. Closing with an empty selection removes all memberships. Assignment does not save or change bookmark notes, tags, or Like.

Deleted collections are removed from the selector draft; a concurrently missing write target never produces a partial save. A load error offers retry, and a write error keeps the selector open. The empty selector saves before opening the Collection library; leaving a detail page with unsaved edits uses its existing discard confirmation, and Cancel preserves the draft. No additional card action menu, bulk assignment, synchronization, or collection export/import is introduced.

Closing after a membership read failure leaves stored memberships untouched and uses the same slide-down animation and completion callback as a successful save. This also applies when the close request arrived during loading and that read subsequently failed. Retry remains available until closing begins.
