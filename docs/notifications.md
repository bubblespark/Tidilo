# Android OS Notifications

Tidilo registers five independently configurable Android notification channels. Settings > Notification uses `ic_notifications` in the standard circular card icon container and directly opens the app's system notification settings. It launches from the host view's Activity context rather than the localized Compose resource context, preserving Back navigation to Tidilo; non-Activity hosts use `FLAG_ACTIVITY_NEW_TASK`. The host Activity requests `POST_NOTIFICATIONS` once when needed; denial or a blocked channel never prevents ingestion. New notification copy is English only.

| Channel | Event | Tap destination |
| --- | --- | --- |
| URLs Pending AI Analysis | More than ten durable READY tasks, including RSS | Import |
| Import Errors | Manual import failure before Ready admission, or aggregated RSS article acquisition failures | Import |
| RSS Scheduled Updates | Daily fetch or Fetch Now logical operation completes | Library |
| RSS Errors | Feed XML acquisition/parsing failure disables a subscription | RSS Settings |
| AICore Errors | Actionable quota or terminal inference failure | Import |

## Ready count

`ImportTaskDao.observeReadyNotificationCount` counts `status = 'READY'` across manual and RSS tasks. Acquisition, WAITING_AI, PROCESSING_AI, and ERROR are excluded. Application-scoped observation posts one normal notification at eleven tasks, updates that same notification silently while above ten, and cancels/rearms at ten or fewer. A locally persisted episode flag prevents every process restart from alerting again. A user-dismissed notification stays dismissed until the count falls below the threshold and rises again.

The body is `There are N URLs awaiting AI analysis.` followed by `Tap to open for AI analysis.` on a new line. There is no promoted notification permission, ongoing notification, status-bar pill, configurable threshold, or app-specific notification switch. The small icon is the supplied `ic_bookmark` vector.

## RSS logical operations

Daily execution and Fetch Now use identical summary/error aggregation. Registration's initial feed acquisition emits aggregated errors but no completion summary. RSS workers do not invoke AICore.

Room 13 adds `rss_notification_runs` and `rss_notification_items`. Each article's notification outcome is committed atomically with its Direct save, AI admission, or acquisition error. The run/item primary key prevents retry duplication. Staging records ownership by the original unfinished logical run, so resumed acquisition workers and explicit article retries update that original run. A recovered article replaces its earlier error outcome. Duplicate, policy-excluded, or queue-cap-discarded articles resolve previous errors without increasing successful-save counts. XML disable events belong to the current operation and are committed with the feed state. Transient Wi-Fi waits, cancellation, time-slice yields, and stale subscription operations do not become errors.

An unfinished logical run retains its original acquisition boundary across worker retries and midnight. Daily requests persist the scheduled boundary in WorkManager input, just as Fetch Now persists its request timestamp. Legacy periodic-request retries can recover their last completed run across a new schedule boundary. A completed run is reused if WorkManager retries delivery before recording success, preventing a second zero-item summary. Completion snapshots the number of this run's AI admissions still in READY; later foreground processing does not change that snapshot. Successfully committed Direct saves are counted separately, without subtracting whole-library counts or including duplicates, excluded items, or rolled-back saves.

The completion body is:

```text
RSS import completed.
Saved directly: N items.
Pending AI analysis: M items.
```

The worker publishes one Import Errors notification for all outstanding article errors in the operation, even for hundreds of articles. It includes the failed-article count and up to three distinct detailed reasons. Article errors retain the existing retry behavior and do not disable the subscription. XML errors produce one RSS Errors notification with:

```text
Some RSS feeds have been disabled because they failed to import.
Tap here to view details.
```

Completed unpublished runs form a local outbox, reconciled on application startup/foreground return and worker completion. If the final feed was disabled before a crashed worker could finish its run, foreground/startup reconciliation closes the now-inactive runs and recovers their notices even if scheduling cancels the workers. Stable run tags and category IDs make replay replace the same notices without another alert. Blocked notices are not saved for unexpected later delivery. Published accounting older than seven days is pruned.

## Manual imports and AICore

The common import outcome path reports invalid HTTP/HTTPS input, acquisition/HTTP failures, duplicate rejection, and other pre-admission failures with a concrete error reason. ShareReceiverActivity only reports invalid share envelopes/text itself; repository failures are not reported twice. The former per-Ready success and `Bookmark added` OS notifications and legacy Import Notifications channel are removed.

Quota failures and terminal BUSY/BACKGROUND_USE_BLOCKED retry exhaustion, or other classified inference failures, publish AICore Errors after task transitions. Routine retries, background pauses, cancellation, acquisition errors, storage errors, and successful configured fallback do not generate AI error alerts. Existing retry/fallback behavior and quota dialogs remain unchanged.

Each notification uses an immutable destination-specific PendingIntent. MainActivity consumes destination intents on cold start and `onNewIntent`, opening Import, Library, or RSS Settings as specified. ImportScreen's bell icon, Room history, read/clear operations, and ordinary Snackbar/Toast feedback remain independent of these OS channels.
