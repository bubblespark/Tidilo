# Android OS Notifications

Tidilo registers three independently configurable Android notification channels. Settings > Notification uses `ic_notifications` in the standard circular card icon container and directly opens the app's system notification settings. It launches from the host view's Activity context rather than the localized Compose resource context, preserving Back navigation to Tidilo; non-Activity hosts use `FLAG_ACTIVITY_NEW_TASK`. The host Activity requests `POST_NOTIFICATIONS` once when needed; denial or a blocked channel never prevents ingestion.

| Channel | Event | Tap destination |
| --- | --- | --- |
| Import Errors | Manual import failure, or aggregated RSS article acquisition failures | Notification |
| RSS Scheduled Updates | Daily fetch or Fetch Now logical operation completes | Library |
| RSS Errors | Feed XML acquisition/parsing failure disables a subscription | RSS Settings |

Startup cancels legacy AI-waiting and AICore error notifications and removes their channels. An unpublished RSS summary waits for eligible prepared legacy AI items to leave the recovery queue. A successful recovery records its direct save in the same transaction.

## RSS logical operations

Daily execution and Fetch Now use identical summary/error aggregation. Registration's initial feed acquisition emits aggregated errors but no completion summary. RSS workers do not invoke AICore.

Room 13 adds `rss_notification_runs` and `rss_notification_items`. Each article's notification outcome is committed atomically with its direct save or acquisition error. The run/item primary key prevents retry duplication. Staging records ownership by the original unfinished logical run, so resumed acquisition workers and explicit article retries update that original run. A recovered article replaces its earlier error outcome. Duplicate or policy-excluded articles resolve previous errors without increasing successful-save counts. XML disable events belong to the current operation and are committed with the feed state. Transient Wi-Fi waits, cancellation, time-slice yields, and stale subscription operations do not become errors.

An unfinished logical run retains its original acquisition boundary across worker retries and midnight. Daily requests persist the scheduled boundary in WorkManager input, just as Fetch Now persists its request timestamp. Legacy periodic-request retries can recover their last completed run across a new schedule boundary. A completed run is reused if WorkManager retries delivery before recording success, preventing a second zero-item summary. Completion counts successfully committed direct saves, without subtracting whole-library counts or including duplicates, excluded items, or rolled-back saves.

The completion body is:

```text
RSS import completed.
Saved directly: N items.
```

The worker publishes one Import Errors notification for all outstanding article errors in the operation, even for hundreds of articles. It includes the failed-article count and up to three distinct detailed reasons. Article errors retain the existing retry behavior and do not disable the subscription. XML errors produce one RSS Errors notification with:

```text
Some RSS feeds have been disabled because they failed to import.
Tap here to view details.
```

Completed unpublished runs form a local outbox, reconciled on application startup/foreground return and worker completion. If the final feed was disabled before a crashed worker could finish its run, foreground/startup reconciliation closes the now-inactive runs and recovers their notices even if scheduling cancels the workers. Stable run tags and category IDs make replay replace the same notices without another alert. Blocked notices are not saved for unexpected later delivery. Published accounting older than seven days is pruned.

## Manual imports

The common import outcome path reports invalid HTTP/HTTPS input, acquisition/HTTP failures, duplicate rejection, and other import failures with a concrete error reason. ShareReceiverActivity only reports invalid share envelopes/text itself; repository failures are not reported twice. The former queued success and `Bookmark added` OS notifications and legacy Import Notifications channel are removed.

Manual AI review reports generation errors inside the review screen.

Each notification uses an immutable destination-specific PendingIntent. MainActivity consumes destination intents on cold start and `onNewIntent`, opening Notification, Library, or RSS Settings as specified. Legacy import destination extras are normalized to Notification. The Library rail's bell opens the in-app Notification list; Room history and ordinary Snackbar/Toast feedback remain independent of these OS channels. See [Library Navigation](library_navigation.md) for the combined history/import-error/XML-error list, per-item Retry/Delete menus and second-precision creation timestamps.
