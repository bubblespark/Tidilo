# Library Navigation

Status: Implemented.

## Intent and scope

Make Library the only root screen. Replace the floating navigation bar with a completely dismissible Material 3 Expressive modal rail. Keep existing bookmark storage, filters, search, direct ingestion, manual AI review, RSS behavior, themes, and localization.

## Approaches

- Selected: `ModalWideNavigationRail` with `hideOnCollapse = true`. It preserves the full Library width while closed and overlays content while open.
- Considered: `ModalNavigationDrawer`, which provides the desired interaction but is superseded by the expanded rail in Expressive guidance.
- Rejected for this phone layout: a persistent collapsed rail. Its icon column reduces the width available to the existing two-column bookmark grid.

## Library and rail

- Library alone exposes the rail through a top-left menu icon. Preserve the existing title, subtitle, header styling, and title interaction. Its only top-right action is the current list/grid display-mode icon.
- Remove the floating navigation bar and filter-mode action/menu. The bottom-right Search action is always the current contracted size: 56dp container and 28dp icon. Scrolling does not resize or reposition it. Respect system insets, summary-peek overlays, and minimum touch targets.
- The rail header contains Tidilo at the left and the existing `ic_notifications` action at the right. Only this header remains fixed while rail content scrolls.
- Scrollable content order: Search; the non-clickable Filters heading; Newest first; Domain; RSS; Collection; Manual Import; Settings. All entries except the section heading have an icon and label. The active filter is selected.
- Manual Import and Settings sit at the bottom of the scroll content, in that order. On tall screens, flexible space places them near the bottom. On short screens or large fonts, they scroll with the content; they are not a pinned footer.
- Selecting a filter changes Library's existing filter state and closes the rail. Preserve existing domain/feed/collection drill-down behavior. No expandable subgroups are requested in this revision.
- Opening the notification action or another destination closes the rail. Dismissal returns to the same Library. Back first dismisses an open rail.

## Secondary destinations

- Search, Notification, Manual Import, and Settings are children of Library. They have Back navigation and do not expose the rail or floating Search action. Existing deeper Settings pages remain nested under Settings.
- Search retains its input, history, tag selection, results, and detail navigation. Returning to Library restores its filter and scroll position.
- Manual Import opens the existing URL-entry dialog from the rail; it does not introduce an Import overview screen. Keep draft, validation, dismissal, and direct-save behavior. The dialog retains the existing URL-entry interaction.
- Notification has a Back button, title, top-right Clear action, and one newest-first list. Include saved notification history, retryable import ERROR tasks (including article HTTP failures), and RSS XML acquisition errors (including feed HTTP failures). No tabs or Import groups.
- Each row displays its creation date and time to second precision in smaller secondary text below the main content. Use `yyyy/MM/dd HH:mm:ss` in the device's current time zone. Use notification.createdAt for history, task.createdAt for import failures, and the persisted failed-attempt timestamp for RSS XML failures. Never substitute the time the screen was opened. Preserve long error/notification content and URL wrapping.
- Import and RSS XML error rows have a trailing three-dot overflow button. Its menu contains Retry and Delete; Retry is no longer a permanently visible row action. Ordinary informational history does not gain an unsupported Retry action. Preserve stable row identity while a menu is open, and disable duplicate actions while a request is in flight.
- Delete removes the selected notification/error item. For import errors, use the existing error-task deletion path. For RSS XML errors, dismiss the selected error without deleting the RSS subscription, its bookmarks, or changing its enabled state. Retry the exact import task or RSS feed, using their IDs rather than URL matching. Retry of XML acquisition must explicitly recover the disabled subscription through the existing scheduling/network policy; a repeated HTTP failure produces an actionable row again.
- Clear semantics: clear saved notification history and its unread state, but preserve unresolved tasks and their retry actions. Disable Clear when no saved history exists. Opening Notification retains the existing mark-as-read behavior. Clear does not silently discard unresolved error tasks.
- Reuse stable notification, task and RSS feed IDs; do not associate retries by URL. A successful retry removes the live error row; a repeated failure remains retryable. Retry uses the current repository/RSS behavior and does not introduce automatic AI analysis.
- Notification history links keep existing bookmark-detail resolution and tolerate missing bookmarks. Preserve existing history retention limits and add no database migration.

## External entry points and constraints

- Normal launch starts at Library. The optional `TidiloApp(sharedUrl)` path imports once and returns to Library. `ShareReceiverActivity` keeps its current background-ingestion-and-finish behavior.
- Route old `import` notification intents to Notification on cold and warm start. New import error notices target Notification; manual AI review retains its existing in-screen feedback. Existing RSS completion and RSS settings destinations retain their behavior. OS channels remain separate from the in-app history.
- Use existing Compose Material 3, Navigation Compose, Room, Coroutines/Flow, and shared theme surfaces. No dependency, version, or application Gradle configuration changes. All content remains local; use English default resources and Japanese translations.
- The implementation uses focused navigation and notification components. Automated coverage includes route compatibility, error generations, storage completion/failure, draft restoration, fixed Search geometry, Library position restoration, and compact/large-font rail access.
