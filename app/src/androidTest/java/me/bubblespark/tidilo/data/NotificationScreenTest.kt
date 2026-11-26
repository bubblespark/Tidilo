package me.bubblespark.tidilo.data

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.data.db.ImportNotificationEntity
import me.bubblespark.tidilo.data.db.RssFeedEntity
import me.bubblespark.tidilo.ui.screens.importscreen.ImportViewModel
import me.bubblespark.tidilo.ui.screens.notifications.NotificationContent
import me.bubblespark.tidilo.ui.screens.notifications.NotificationListItem
import me.bubblespark.tidilo.ui.screens.notifications.NotificationScreen
import me.bubblespark.tidilo.ui.screens.notifications.NotificationSections
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class NotificationScreenTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun savedDuplicateUsesItsStoredIdAndReportsAStaleTarget(): Unit = runBlocking {
        val job = SupervisorJob()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
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
            composeRule.setContent {
                TidiloTheme(blurEnabled = false, dynamicColor = false) {
                    NotificationScreen(viewModel, {}, { opened.value = it })
                }
            }
            val open = openSavedPage()
            open.performClick()
            composeRule.waitUntil(5_000) { opened.value == bookmark.id }
            bookmarks.deleteById(bookmark.id)
            bookmarks.insertOrUpdate(bookmark.copy(id = "replacement-id"))
            open.performClick()
            await(string(R.string.notification_bookmark_unavailable))
            assertEquals(bookmark.id, opened.value)
        } finally {
            job.cancelAndJoin()
            database.close()
        }
    }

    @Test fun legacyDuplicateOpensByUrlAndReportsUnavailable(): Unit = runBlocking {
        val job = SupervisorJob()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
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
            composeRule.setContent {
                TidiloTheme(blurEnabled = false, dynamicColor = false) {
                    NotificationScreen(viewModel, {}, { opened += it })
                }
            }
            await(string(R.string.notification_history_duplicate_library))
            val open = openSavedPage()
            open.performClick()
            composeRule.waitUntil(5_000) { opened.isNotEmpty() }
            assertEquals(listOf(bookmark.id), opened)
            bookmarks.deleteById(bookmark.id)
            open.performClick()
            await(string(R.string.notification_bookmark_unavailable))
            assertEquals(listOf(bookmark.id), opened)
        } finally {
            job.cancelAndJoin()
            database.close()
        }
    }

    @Test fun staticSectionsRenderTheirRowsAndActions() {
        val sections = mutableStateOf(NotificationSections(emptyList(), listOf(
            history(id = 1, domain = "Unknown fixture", message = "Saved", createdAt = 2),
            history(id = 2, domain = "Queue fixture", message = ImportNotificationMessages.DUPLICATE_IN_QUEUE, createdAt = 1))))
        composeRule.setContent {
            TidiloTheme(blurEnabled = false, dynamicColor = false) {
                NotificationContent(sections.value, emptySet(), {}, {}, {}, {}, {})
            }
        }

        // Unknown and queue history rows have no action.
        await(string(R.string.notification_history_duplicate_queue))
        assertTrue(nodes(hasText("Saved")).isNotEmpty())
        assertTrue(nodes(hasText(ImportNotificationMessages.DUPLICATE_IN_QUEUE)).isEmpty())
        assertTrue(nodes(hasText(string(R.string.import_open_saved_page))).isEmpty())
        listOf("Unknown fixture", "Queue fixture", "Saved").forEach { text ->
            assertTrue("$text must not be tappable",
                nodes(hasText(text) and (hasClickAction() or hasAnyAncestor(hasClickAction()))).isEmpty())
        }

        // A mixed list separates Needs attention from History.
        sections.value = NotificationSections(
            needsAttention = listOf(
                NotificationListItem.Failure(ImportTask("task", "https://task.invalid/page", "Task fixture",
                    TaskStatus.ERROR, "Failed", errorMessage = "Article HTTP 503", createdAt = 300)),
                NotificationListItem.RssXmlFailure(RssFeedEntity("feed", "https://feed.invalid/rss", "Feed fixture",
                    error = "HTTP 404", lastAttemptAt = 200))),
            history = listOf(history(id = 1, domain = "History fixture", message = "Saved", createdAt = 400)))
        await(string(R.string.notification_failure_server_unavailable))
        val attention = string(R.string.notification_section_attention)
        val historyHeader = string(R.string.notification_section_history)
        fun node(text: String) = nodes(hasText(text)).first()
        assertNotNull(node(attention).config.getOrNull(SemanticsProperties.Heading))
        assertNotNull(node(historyHeader).config.getOrNull(SemanticsProperties.Heading))
        val order = listOf(attention, "Task fixture", "Feed fixture", historyHeader, "History fixture")
            .map { node(it).boundsInRoot.top }
        assertEquals("Rows must follow the section order", order.sorted(), order)
        listOf(R.string.notification_status_import_failed, R.string.notification_status_feed_disabled,
            R.string.notification_failure_not_found).forEach { assertTrue(nodes(hasText(string(it))).isNotEmpty()) }

        // A blank diagnostic shows only the fallback.
        sections.value = NotificationSections(listOf(NotificationListItem.Failure(ImportTask("blank", "https://blank.invalid",
            "Blank fixture", TaskStatus.ERROR, "", errorMessage = null, createdAt = 1))), emptyList())
        await(string(R.string.notification_failure_unknown))
        assertTrue(nodes(hasText(string(R.string.notification_details_show))).isEmpty())
        assertTrue("No empty text may be rendered", nodes(SemanticsMatcher("empty text") { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.isEmpty() } == true
        }).isEmpty())

        // Row actions name their item.
        val long = "Unexpected parser failure: " + "segment ".repeat(80)
        sections.value = NotificationSections(listOf(
            NotificationListItem.Failure(ImportTask("first", "https://first.invalid", "First fixture",
                TaskStatus.ERROR, "Failed", errorMessage = long, createdAt = 2)),
            NotificationListItem.RssXmlFailure(RssFeedEntity("second", "https://second.invalid/rss",
                "Second fixture", error = long, lastAttemptAt = 1))), emptyList())
        await("Second fixture")
        listOf("First fixture", "Second fixture").forEach { title ->
            listOf(R.string.notification_retry_item, R.string.notification_details_show_item,
                R.string.notification_more_actions_item).forEach { id ->
                assertEquals(1, nodes(hasContentDescription(context.getString(id, title))).size)
            }
        }
    }

    @Test fun clearHistoryKeepsFailuresAndDisablesWithFailuresOnly() {
        val failure = NotificationListItem.Failure(ImportTask("task", "https://task.invalid/page", "Task fixture",
            TaskStatus.ERROR, "Failed", errorMessage = "Article HTTP 503", createdAt = 1))
        val sections = mutableStateOf(NotificationSections(listOf(failure),
            listOf(history(id = 1, domain = "History fixture", message = "Saved", createdAt = 2))))
        composeRule.setContent {
            TidiloTheme(blurEnabled = false, dynamicColor = false) {
                NotificationContent(sections.value, emptySet(), {}, {
                    sections.value = sections.value.copy(history = emptyList())
                }, {}, {}, {})
            }
        }
        val clear = composeRule.onNode(hasText(string(R.string.notification_clear_history)))
        clear.assertIsEnabled().performClick()
        composeRule.onNodeWithText(string(R.string.notification_section_history)).assertDoesNotExist()
        assertTrue(nodes(hasText("Task fixture")).isNotEmpty())
        assertTrue(nodes(hasText("History fixture")).isEmpty())
        clear.assertIsNotEnabled()
        sections.value = NotificationSections(emptyList(), emptyList())
        await(string(R.string.notification_empty))
    }

    @Test fun longDiagnosticTogglesAndRestoresExpandedState() {
        val diagnostic = "Unexpected parser failure: " + "segment ".repeat(80)
        val sections = NotificationSections(listOf(NotificationListItem.Failure(ImportTask("long", "https://long.invalid",
            "Long fixture", TaskStatus.ERROR, "Failed", errorMessage = diagnostic, createdAt = 1))), emptyList())
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            TidiloTheme(blurEnabled = false, dynamicColor = false) {
                NotificationContent(sections, emptySet(), {}, {}, {}, {}, {})
            }
        }
        val show = composeRule.onNode(hasText(string(R.string.notification_details_show)) and hasClickAction())
        assertEquals(string(R.string.notification_details_collapsed),
            show.fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
        show.performClick()
        await(string(R.string.notification_details_hide))
        restoration.emulateSavedInstanceStateRestore()
        val hide = composeRule.onNode(hasText(string(R.string.notification_details_hide)) and hasClickAction())
        assertEquals(string(R.string.notification_details_expanded),
            hide.fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
    }

    @Test fun replacedShortDiagnosticDropsToggle() {
        val task = ImportTask("same", "https://same.invalid", "Same fixture", TaskStatus.ERROR, "Failed",
            errorMessage = "Unexpected parser failure: " + "segment ".repeat(80), createdAt = 1)
        val sections = mutableStateOf(NotificationSections(listOf(NotificationListItem.Failure(task)), emptyList()))
        composeRule.setContent {
            TidiloTheme(blurEnabled = false, dynamicColor = false) {
                NotificationContent(sections.value, emptySet(), {}, {}, {}, {}, {})
            }
        }
        await(string(R.string.notification_details_show))
        sections.value = NotificationSections(listOf(NotificationListItem.Failure(task.copy(errorMessage = "HTTP 504"))),
            emptyList())
        await("HTTP 504")
        composeRule.onNodeWithText(string(R.string.notification_details_show), useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test fun busyRetryIsDisabledAndTargetsExactRow() {
        val row = NotificationListItem.Failure(ImportTask("selected", "https://example.invalid/page", "Page",
            TaskStatus.ERROR, "Failed", errorMessage = "HTTP 503", createdAt = 0))
        val busy = mutableStateOf(setOf(row.key))
        val retried = mutableListOf<String>()
        composeRule.setContent {
            TidiloTheme(blurEnabled = false, dynamicColor = false) {
                NotificationContent(NotificationSections(listOf(row), emptyList()), busy.value, {}, {},
                    { retried += it.key }, {}, {})
            }
        }
        val retry = composeRule.onNode(hasText(string(R.string.common_retry)) and hasClickAction())
        retry.assertIsNotEnabled()
        assertEquals(string(R.string.notification_retrying),
            retry.fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
        busy.value = emptySet()
        composeRule.waitUntil(5_000) {
            retry.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Disabled) == null
        }
        assertNull(retry.fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
        retry.performClick()
        assertEquals(listOf("task:selected"), retried)
    }

    @Test fun newerFailureClosesAnAlreadyOpenActionMenu() {
        val row = mutableStateOf(NotificationListItem.RssXmlFailure(RssFeedEntity("feed", "https://xml.invalid/feed",
            "XML", error = "HTTP 503", lastAttemptAt = 100)))
        composeRule.setContent {
            TidiloTheme(blurEnabled = false, dynamicColor = false) {
                NotificationContent(NotificationSections(listOf(row.value), emptyList()), emptySet(), {}, {}, {}, {}, {})
            }
        }
        composeRule.onNode(hasTestTag("notification_actions:feed:feed")).performClick()
        val delete = string(R.string.common_delete)
        await(delete)
        row.value = NotificationListItem.RssXmlFailure(row.value.feed.copy(error = "HTTP 504", lastAttemptAt = 200))
        await("HTTP 504")
        composeRule.onNodeWithText(delete, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun errorOverflowDisplaysDateAndDeletesSelectedTask() {
        var deleted: String? = null
        val row = NotificationListItem.Failure(ImportTask("selected", "https://example.invalid/page", "Page",
            TaskStatus.ERROR, "Failed", errorMessage = "HTTP 503", createdAt = 0))
        composeRule.setContent {
            TidiloTheme(blurEnabled = false, dynamicColor = false) {
                NotificationContent(NotificationSections(listOf(row), emptyList()), emptySet(), {}, {}, {},
                    { deleted = it.key }, {})
            }
        }
        assertTrue(nodes(SemanticsMatcher("1970 date") { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.contains("1970/01/01") && it.text.endsWith(":00") } == true
        }).isNotEmpty())
        composeRule.onNode(hasTestTag("notification_actions:task:selected")).performClick()
        composeRule.onNode(hasText(string(R.string.common_delete)) and hasClickAction()).performClick()
        assertEquals("task:selected", deleted)
    }

    private fun string(id: Int) = context.getString(id)

    private fun nodes(matcher: SemanticsMatcher): List<SemanticsNode> =
        composeRule.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes()

    private fun await(text: String) = composeRule.waitUntil(5_000) { nodes(hasText(text)).isNotEmpty() }

    private fun openSavedPage() = composeRule.onNode(hasText(string(R.string.import_open_saved_page)) and hasClickAction())
        .also { await(string(R.string.import_open_saved_page)) }

    private fun history(id: Long, domain: String, message: String, createdAt: Long, url: String = "https://$domain.invalid",
        bookmarkId: String? = null) = NotificationListItem.History(ImportNotificationEntity(id = id, type = "INFO",
        domain = domain, url = url, message = message, existingBookmarkId = bookmarkId, createdAt = createdAt))
}
