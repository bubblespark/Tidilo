package me.bubblespark.tidilo.data

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
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

    @Test fun historyExposesButtonRoleAndOpensSelectedNotification(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        val row = NotificationListItem.History(me.bubblespark.tidilo.data.db.ImportNotificationEntity(
            id = 42, type = "INFO", domain = "History fixture", url = "https://example.invalid", message = "Saved"))
        var opened: String? = null
        try {
            host.main { activity.setContent { MaterialTheme {
                NotificationContent(listOf(row), false, emptySet(), {}, {}, {}, {}, { opened = it.key })
            } } }
            val label = host.await(activity) { host.text(it, "History fixture") }
            host.main {
                val button = generateSequence(label) { it.parent }.first {
                    it.config.getOrNull(SemanticsActions.OnClick) != null
                }
                assertEquals(androidx.compose.ui.semantics.Role.Button, button.config.getOrNull(SemanticsProperties.Role))
                assertTrue(button.config[SemanticsActions.OnClick].action!!.invoke())
                assertEquals(row.key, opened)
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
                NotificationContent(listOf(row.value), false, emptySet(), {}, {}, {}, {}, {})
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
                NotificationContent(listOf(row), false, emptySet(), {}, {}, {}, { deleted = it.key }, {})
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
}
