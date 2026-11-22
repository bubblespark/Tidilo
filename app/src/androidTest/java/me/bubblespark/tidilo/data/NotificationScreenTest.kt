package me.bubblespark.tidilo.data

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.data.db.ImportNotificationEntity
import me.bubblespark.tidilo.data.db.RssFeedEntity
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.ui.screens.notifications.NotificationContent
import me.bubblespark.tidilo.ui.screens.notifications.NotificationListItem
import me.bubblespark.tidilo.ui.screens.notifications.NotificationScreen
import me.bubblespark.tidilo.ui.screens.notifications.NotificationSections
import me.bubblespark.tidilo.ui.screens.importscreen.ImportViewModel
import org.junit.Assert.*
import org.junit.Test

class NotificationScreenTest {
    @Test fun savedDuplicateUsesItsStoredIdAndReportsAStaleTarget(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        val job = SupervisorJob()
        val database = Room.inMemoryDatabaseBuilder(host.context, AppDatabase::class.java).build()
        val bookmarks = database.bookmarkDao()
        val history = database.importNotificationDao()
        val url = "https://history.invalid/page"
        val bookmark = BookmarkEntity("exact-id", "Saved", url, "", createdAt = 1, updatedAt = 1)
        val opened = mutableStateOf<String?>(null)
        try {
            bookmarks.insertOrUpdate(bookmark)
            history.insert(ImportNotificationEntity(type = "WARNING", domain = "Duplicate fixture", url = url,
                message = "Duplicate URL (Already in Library)", existingBookmarkId = bookmark.id))
            val repository = ImportRepository(taskDao = database.importTaskDao(), bookmarkDao = bookmarks,
                notificationDao = history, database = database, repositoryScope = CoroutineScope(coroutineContext + job))
            val viewModel = ImportViewModel(repository)
            host.main { activity.setContent { MaterialTheme {
                NotificationScreen(viewModel, {}, { opened.value = it })
            } } }
            val open = host.await(activity) { host.text(it, activity.getString(R.string.import_open_saved_page)) }
            fun openSavedPage() = host.main {
                val button = generateSequence(open) { it.parent }.first { it.config.getOrNull(SemanticsActions.OnClick) != null }
                assertTrue(button.config[SemanticsActions.OnClick].action?.invoke() == true)
            }
            openSavedPage()
            withTimeout(5000) { while (opened.value != bookmark.id) delay(20) }
            bookmarks.deleteById(bookmark.id)
            bookmarks.insertOrUpdate(bookmark.copy(id = "replacement-id"))
            openSavedPage()
            host.await(activity) { host.text(it, activity.getString(R.string.notification_bookmark_unavailable)) }
            assertEquals(bookmark.id, opened.value)
        } finally {
            host.main { activity.finish() }
            job.cancelAndJoin()
            database.close()
        }
    }

    @Test fun legacyDuplicateOpensByUrlAndReportsUnavailable(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        val job = SupervisorJob()
        val database = Room.inMemoryDatabaseBuilder(host.context, AppDatabase::class.java).build()
        val bookmarks = database.bookmarkDao()
        val history = database.importNotificationDao()
        val url = "https://legacy.invalid/page"
        val bookmark = BookmarkEntity("legacy-id", "Saved", url, "", createdAt = 1, updatedAt = 1)
        val opened = mutableListOf<String>()
        try {
            bookmarks.insertOrUpdate(bookmark)
            history.insert(ImportNotificationEntity(type = "WARNING", domain = "Legacy fixture", url = url,
                message = ImportNotificationMessages.DUPLICATE_IN_LIBRARY, existingBookmarkId = null))
            val repository = ImportRepository(taskDao = database.importTaskDao(), bookmarkDao = bookmarks,
                notificationDao = history, database = database, repositoryScope = CoroutineScope(coroutineContext + job))
            val viewModel = ImportViewModel(repository)
            host.main { activity.setContent { MaterialTheme {
                NotificationScreen(viewModel, {}, { opened += it })
            } } }
            host.await(activity) { host.text(it, activity.getString(R.string.notification_history_duplicate_library)) }
            val open = host.await(activity) { host.text(it, activity.getString(R.string.import_open_saved_page)) }
            host.main { assertTrue(clickable(open).config[SemanticsActions.OnClick].action!!.invoke()) }
            withTimeout(5000) { while (opened.isEmpty()) delay(20) }
            assertEquals(listOf(bookmark.id), opened)
            bookmarks.deleteById(bookmark.id)
            host.main { assertTrue(clickable(open).config[SemanticsActions.OnClick].action!!.invoke()) }
            host.await(activity) { host.text(it, activity.getString(R.string.notification_bookmark_unavailable)) }
            assertEquals(listOf(bookmark.id), opened)
        } finally {
            host.main { activity.finish() }
            job.cancelAndJoin()
            database.close()
        }
    }

    @Test fun unknownAndQueueHistoryHaveNoAction(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        val sections = NotificationSections(emptyList(), listOf(
            history(id = 1, domain = "Unknown fixture", message = "Saved", createdAt = 2),
            history(id = 2, domain = "Queue fixture", message = ImportNotificationMessages.DUPLICATE_IN_QUEUE, createdAt = 1)))
        try {
            host.main { activity.setContent { MaterialTheme {
                NotificationContent(sections, emptySet(), {}, {}, {}, {}, {})
            } } }
            host.await(activity) { host.text(it, activity.getString(R.string.notification_history_duplicate_queue)) }
            host.main {
                val nodes = host.nodes(activity)
                assertTrue(nodes.any { host.text(it, "Saved") })
                assertFalse(nodes.any { host.text(it, ImportNotificationMessages.DUPLICATE_IN_QUEUE) })
                assertFalse(nodes.any { host.text(it, activity.getString(R.string.import_open_saved_page)) })
                listOf("Unknown fixture", "Queue fixture", "Saved").forEach { text ->
                    val node = nodes.first { host.text(it, text) }
                    assertNull("$text must not be tappable",
                        generateSequence(node) { it.parent }.firstOrNull { it.config.getOrNull(SemanticsActions.OnClick) != null })
                }
            }
        } finally { host.main { activity.finish() } }
    }

    @Test fun mixedListSeparatesNeedsAttentionFromHistory(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        val sections = NotificationSections(
            needsAttention = listOf(
                NotificationListItem.Failure(ImportTask("task", "https://task.invalid/page", "Task fixture",
                    TaskStatus.ERROR, "Failed", errorMessage = "Article HTTP 503", createdAt = 300)),
                NotificationListItem.RssXmlFailure(RssFeedEntity("feed", "https://feed.invalid/rss", "Feed fixture",
                    error = "HTTP 404", lastAttemptAt = 200))),
            history = listOf(history(id = 1, domain = "History fixture", message = "Saved", createdAt = 400)))
        try {
            host.main { activity.setContent { MaterialTheme {
                NotificationContent(sections, emptySet(), {}, {}, {}, {}, {})
            } } }
            val attention = host.await(activity) { host.text(it, activity.getString(R.string.notification_section_attention)) }
            host.await(activity) { host.text(it, activity.getString(R.string.notification_failure_server_unavailable)) }
            host.main {
                val nodes = host.nodes(activity)
                fun top(text: String) = nodes.first { host.text(it, text) }.boundsInRoot.top
                val historyHeader = nodes.first { host.text(it, activity.getString(R.string.notification_section_history)) }
                assertNotNull(attention.config.getOrNull(SemanticsProperties.Heading))
                assertNotNull(historyHeader.config.getOrNull(SemanticsProperties.Heading))
                val order = listOf(activity.getString(R.string.notification_section_attention), "Task fixture",
                    "Feed fixture", activity.getString(R.string.notification_section_history), "History fixture").map(::top)
                assertEquals("Rows must follow the section order", order.sorted(), order)
                assertTrue(nodes.any { host.text(it, activity.getString(R.string.notification_status_import_failed)) })
                assertTrue(nodes.any { host.text(it, activity.getString(R.string.notification_status_feed_disabled)) })
                assertTrue(nodes.any { host.text(it, activity.getString(R.string.notification_failure_not_found)) })
            }
        } finally { host.main { activity.finish() } }
    }

    @Test fun clearHistoryKeepsFailuresAndDisablesWithFailuresOnly(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        val failure = NotificationListItem.Failure(ImportTask("task", "https://task.invalid/page", "Task fixture",
            TaskStatus.ERROR, "Failed", errorMessage = "Article HTTP 503", createdAt = 1))
        val sections = mutableStateOf(NotificationSections(listOf(failure),
            listOf(history(id = 1, domain = "History fixture", message = "Saved", createdAt = 2))))
        try {
            host.main { activity.setContent { MaterialTheme {
                NotificationContent(sections.value, emptySet(), {}, {
                    sections.value = sections.value.copy(history = emptyList())
                }, {}, {}, {})
            } } }
            val clearLabel = activity.getString(R.string.notification_clear_history)
            val clear = host.await(activity) { host.text(it, clearLabel) }
            host.main {
                val button = clickable(clear)
                assertNull(button.config.getOrNull(SemanticsProperties.Disabled))
                assertTrue(button.config[SemanticsActions.OnClick].action!!.invoke())
            }
            awaitAbsent(host, activity, activity.getString(R.string.notification_section_history))
            host.main {
                val nodes = host.nodes(activity)
                assertTrue(nodes.any { host.text(it, "Task fixture") })
                assertFalse(nodes.any { host.text(it, "History fixture") })
                assertNotNull(clickableOrSelf(nodes.first { host.text(it, clearLabel) })
                    .config.getOrNull(SemanticsProperties.Disabled))
                sections.value = NotificationSections(emptyList(), emptyList())
            }
            host.await(activity) { host.text(it, activity.getString(R.string.notification_empty)) }
        } finally { host.main { activity.finish() } }
    }

    @Test fun longDiagnosticTogglesAndRestoresExpandedState(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        val diagnostic = "Unexpected parser failure: " + "segment ".repeat(80)
        val sections = NotificationSections(listOf(NotificationListItem.Failure(ImportTask("long", "https://long.invalid",
            "Long fixture", TaskStatus.ERROR, "Failed", errorMessage = diagnostic, createdAt = 1))), emptyList())
        val sourceRegistry = SaveableStateRegistry(null) { true }
        val restoredRegistry = mutableStateOf<SaveableStateRegistry?>(null)
        val showContent = mutableStateOf(true)
        try {
            host.main { activity.setContent { MaterialTheme {
                if (showContent.value) {
                    CompositionLocalProvider(LocalSaveableStateRegistry provides (restoredRegistry.value ?: sourceRegistry)) {
                        NotificationContent(sections, emptySet(), {}, {}, {}, {}, {})
                    }
                }
            } } }
            val show = host.await(activity) { host.text(it, activity.getString(R.string.notification_details_show)) }
            host.main {
                val toggle = clickable(show)
                assertEquals(activity.getString(R.string.notification_details_collapsed),
                    toggle.config.getOrNull(SemanticsProperties.StateDescription))
                assertTrue(toggle.config[SemanticsActions.OnClick].action!!.invoke())
            }
            host.await(activity) { host.text(it, activity.getString(R.string.notification_details_hide)) }
            host.main {
                val saved = sourceRegistry.performSave()
                showContent.value = false
                restoredRegistry.value = SaveableStateRegistry(saved) { true }
            }
            awaitAbsent(host, activity, "Long fixture")
            host.main { showContent.value = true }
            val hide = host.await(activity) { host.text(it, activity.getString(R.string.notification_details_hide)) }
            host.main {
                assertEquals(activity.getString(R.string.notification_details_expanded),
                    clickable(hide).config.getOrNull(SemanticsProperties.StateDescription))
            }
        } finally { host.main { activity.finish() } }
    }

    @Test fun replacedShortDiagnosticDropsToggle(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        val task = ImportTask("same", "https://same.invalid", "Same fixture", TaskStatus.ERROR, "Failed",
            errorMessage = "Unexpected parser failure: " + "segment ".repeat(80), createdAt = 1)
        val sections = mutableStateOf(NotificationSections(listOf(NotificationListItem.Failure(task)), emptyList()))
        try {
            host.main { activity.setContent { MaterialTheme {
                NotificationContent(sections.value, emptySet(), {}, {}, {}, {}, {})
            } } }
            host.await(activity) { host.text(it, activity.getString(R.string.notification_details_show)) }
            host.main {
                sections.value = NotificationSections(listOf(NotificationListItem.Failure(task.copy(errorMessage = "HTTP 504"))),
                    emptyList())
            }
            host.await(activity) { host.text(it, "HTTP 504") }
            delay(400)
            host.main {
                assertFalse("A short replacement must not keep the previous toggle",
                    host.nodes(activity).any { host.text(it, activity.getString(R.string.notification_details_show)) })
            }
        } finally { host.main { activity.finish() } }
    }

    @Test fun blankDiagnosticShowsFallbackOnly(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        val sections = NotificationSections(listOf(NotificationListItem.Failure(ImportTask("blank", "https://blank.invalid",
            "Blank fixture", TaskStatus.ERROR, "", errorMessage = null, createdAt = 1))), emptyList())
        try {
            host.main { activity.setContent { MaterialTheme {
                NotificationContent(sections, emptySet(), {}, {}, {}, {}, {})
            } } }
            host.await(activity) { host.text(it, activity.getString(R.string.notification_failure_unknown)) }
            host.main {
                val nodes = host.nodes(activity, includeOtherWindows = false)
                assertFalse(nodes.any { host.text(it, activity.getString(R.string.notification_details_show)) })
                assertFalse("No empty text may be rendered",
                    nodes.any { node -> node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.isEmpty() } == true })
            }
        } finally { host.main { activity.finish() } }
    }

    @Test fun busyRetryIsDisabledAndTargetsExactRow(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        val row = NotificationListItem.Failure(ImportTask("selected", "https://example.invalid/page", "Page",
            TaskStatus.ERROR, "Failed", errorMessage = "HTTP 503", createdAt = 0))
        val busy = mutableStateOf(setOf(row.key))
        val retried = mutableListOf<String>()
        try {
            host.main { activity.setContent { MaterialTheme {
                NotificationContent(NotificationSections(listOf(row), emptyList()), busy.value, {}, {},
                    { retried += it.key }, {}, {})
            } } }
            val label = host.await(activity) { host.text(it, activity.getString(R.string.common_retry)) }
            host.main {
                val button = clickable(label)
                assertNotNull(button.config.getOrNull(SemanticsProperties.Disabled))
                assertEquals(activity.getString(R.string.notification_retrying),
                    button.config.getOrNull(SemanticsProperties.StateDescription))
                busy.value = emptySet()
            }
            withTimeout(5000) {
                while (true) {
                    var enabled = false
                    host.main {
                        enabled = host.nodes(activity).filter { host.text(it, activity.getString(R.string.common_retry)) }
                            .any { clickable(it).config.getOrNull(SemanticsProperties.Disabled) == null }
                    }
                    if (enabled) break
                    delay(20)
                }
            }
            host.main {
                val button = clickable(host.nodes(activity).first { host.text(it, activity.getString(R.string.common_retry)) })
                assertNull(button.config.getOrNull(SemanticsProperties.StateDescription))
                assertTrue(button.config[SemanticsActions.OnClick].action!!.invoke())
                assertEquals(listOf("task:selected"), retried)
            }
        } finally { host.main { activity.finish() } }
    }

    @Test fun newerFailureClosesAnAlreadyOpenActionMenu(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        val row = mutableStateOf(NotificationListItem.RssXmlFailure(RssFeedEntity("feed", "https://xml.invalid/feed",
            "XML", error = "HTTP 503", lastAttemptAt = 100)))
        try {
            host.main { activity.setContent { MaterialTheme {
                NotificationContent(NotificationSections(listOf(row.value), emptyList()), emptySet(), {}, {}, {}, {}, {})
            } } }
            val menu = host.await(activity) { it.config.getOrNull(SemanticsProperties.TestTag) == "notification_actions:feed:feed" }
            host.main { menu.config[SemanticsActions.OnClick].action?.invoke() }
            host.await(activity) { host.text(it, host.context.getString(R.string.common_delete)) }
            host.main { row.value = NotificationListItem.RssXmlFailure(row.value.feed.copy(error = "HTTP 504", lastAttemptAt = 200)) }
            host.await(activity) { host.text(it, "HTTP 504") }
            delay(400)
            host.main { assertFalse("An old error menu must not target a replacement failure",
                host.nodes(activity).any { host.text(it, host.context.getString(R.string.common_delete)) }) }
        } finally { host.main { activity.finish() } }
    }

    @Test fun errorOverflowDisplaysDateAndDeletesSelectedTask(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        var deleted: String? = null
        val row = NotificationListItem.Failure(ImportTask("selected", "https://example.invalid/page", "Page",
            TaskStatus.ERROR, "Failed", errorMessage = "HTTP 503", createdAt = 0))
        try {
            host.main { activity.setContent { MaterialTheme {
                NotificationContent(NotificationSections(listOf(row), emptyList()), emptySet(), {}, {}, {},
                    { deleted = it.key }, {})
            } } }
            val menu = host.await(activity) { it.config.getOrNull(SemanticsProperties.TestTag) == "notification_actions:task:selected" }
            host.main {
                assertTrue(host.nodes(activity).any { node -> node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.contains("1970/01/01") && it.text.endsWith(":00") } == true })
                assertTrue(menu.config[SemanticsActions.OnClick].action?.invoke() == true)
            }
            val delete = host.await(activity) { host.text(it, host.context.getString(R.string.common_delete)) }
            host.main {
                var target = delete
                while (target.config.getOrNull(SemanticsActions.OnClick) == null) target = requireNotNull(target.parent)
                target.config[SemanticsActions.OnClick].action?.invoke()
                assertEquals("task:selected", deleted)
            }
        } finally { host.main { activity.finish() } }
    }

    private fun history(id: Long, domain: String, message: String, createdAt: Long, url: String = "https://$domain.invalid",
        bookmarkId: String? = null) = NotificationListItem.History(ImportNotificationEntity(id = id, type = "INFO",
        domain = domain, url = url, message = message, existingBookmarkId = bookmarkId, createdAt = createdAt))

    private fun clickable(node: SemanticsNode): SemanticsNode =
        generateSequence(node) { it.parent }.first { it.config.getOrNull(SemanticsActions.OnClick) != null }

    private fun clickableOrSelf(node: SemanticsNode): SemanticsNode =
        generateSequence(node) { it.parent }.firstOrNull { it.config.getOrNull(SemanticsActions.OnClick) != null } ?: node

    private suspend fun awaitAbsent(host: HeaderTestHost, activity: ComponentActivity, text: String) = withTimeout(5000) {
        while (true) {
            var present = true
            host.main { present = host.nodes(activity).any { host.text(it, text) } }
            if (!present) break
            delay(20)
        }
    }
}
