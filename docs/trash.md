# Trash

Trash is a secondary destination opened from the Library navigation rail immediately below Search and before Filters. It has Back navigation and no Library rail or floating Search action.

## List and actions

The single-column, text-only list shows each deleted bookmark's title, URL and deletion time. Rows sort by deletion time descending, with descending ID as a stable tie-breaker. Time is displayed as `yyyy/MM/dd HH:mm` in the current device time zone. Existing NSFW visibility settings apply.

Rows have no tap action and do not open Detail. The trailing overflow menu contains Restore and Delete. Both require a confirmation dialog; cancelling performs no writes. Delete permanently removes only that Trash record. There are no bulk actions. Title and overflow icon colors explicitly follow the theme foreground, including when the parent has transparent content.

A small Already in Library label marks URLs present in the active bookmarks table, across manual and RSS sources and including hidden NSFW records. Restore is disabled for those rows. Matching uses exact stored URL equality, following existing ingestion normalization; no new URL equivalence rules are introduced. Other Trash rows alone do not count as active duplicates.

## Persistence and restoration

Trash continues to use `trashed_bookmarks`. Its existing startup purge keeps records for 30 days, and moving items to Trash enforces the existing 100-item limit. This feature adds queries and transactions without a schema migration.

Restore re-reads the selected row and checks active URL and ID ownership in the same Room transaction. It refuses newly conflicting records without modifying either version. A matching QUEUED, PROCESSING or ERROR import also prevents restoration with actionable feedback; completed import history does not. Import conflicts are not labeled as active Library duplicates.

A successful restoration inserts the saved ID using the canonical bookmark DAO, rebuilds derived search/tag fields, and removes its Trash row atomically. It never invokes URL replacement, HTML acquisition, or AI analysis. Failed inserts/deletes roll back. Stale actions and concurrent Restore/Delete cannot remove an active bookmark.

The saved content, note, classification, entity identities, NSFW setting, original times, engagement counters, last interaction, image references and RSS metadata are preserved. Entity display spelling follows the existing canonical persistence policy. Like remains false and Collection memberships are not restored because the existing Trash producer clears them. RSS ownership is retained even for disabled/deleted feeds; restoration does not reactivate subscriptions or reset article age. Subsequent RSS retention still applies.

Permanent deletion removes only the selected Trash row and does not delete shared cache files or matching active bookmarks. Existing independent cache maintenance remains responsible for unused files.

## Lifecycle and errors

Live Room queries update duplicate labels when either table changes. A screen-scoped ViewModel saves confirmation target ID/action, guards double submission, revalidates current rows and delegates final conflict safety to the storage transaction. Recreation restores the confirmation without automatically submitting it. Read failures expose Retry and block writes against stale rows; cancelling a confirmation preserves the read error so Retry remains available. Write failures retain the confirmation and target for another attempt. Missing or newly conflicting targets produce feedback without replacement.

The existing Library Detail is unchanged; Trash has no separate Detail renderer, mode or route. No dependency, version, Gradle configuration, serialization/reflection or release-only behavior is added; no R8/Release-specific risk is identified.

Retry awaits completion of the previous startup attempt and the requested initialization retry before subscribing again. This prevents a stale startup error from immediately ending the new observation. Repeated Retry actions are ignored while loading; a new failure remains recoverable through the same error state.
