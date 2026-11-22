package me.bubblespark.tidilo.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import me.bubblespark.tidilo.data.db.*
import me.bubblespark.tidilo.data.rss.RssRepository
import me.bubblespark.tidilo.data.rss.RssHttpPage
import me.bubblespark.tidilo.parser.ArticleHttpFailure
import me.bubblespark.tidilo.ui.screens.notifications.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class NotificationLifecycleTest {
    @Test fun transientXmlFailureWithAnOperationCanBeExplicitlyRetried(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val feed = RssFeedEntity("transient", "https://xml.invalid/feed", "Feed", acquisitionId = "previous")
            db.rssDao().putFeed(feed)
            val requests = mutableListOf<Pair<String, Boolean>>()
            val repository = RssRepository(context, db,
                requestOverride = { url, _, _ -> RssHttpPage(503, url, "Unavailable") },
                scheduleChanged = {}, enqueueInitial = { id, _, mobile -> requests += id to mobile })
            repository.fetchAll(0, onlyFeedId = feed.id, authorizedOperation = "previous")
            val failure = requireNotNull(db.rssDao().feed(feed.id))
            assertNotNull(failure.error)
            assertEquals("previous", failure.acquisitionId)
            repository.retryXmlError(feed.id)
            assertEquals(listOf("transient" to false), requests)
        } finally { db.close() }
    }

    @Test fun actualHttpFailureRemainsRetryableAfterHistoryIsCleared(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val job = SupervisorJob()
        try {
            val taskDao = db.importTaskDao()
            val attempts = AtomicInteger()
            val retryStarted = CompletableDeferred<Unit>()
            val releaseRetry = CompletableDeferred<Unit>()
            val repository = ImportRepository(taskDao = taskDao, bookmarkDao = db.bookmarkDao(),
                notificationDao = db.importNotificationDao(), database = db,
                repositoryScope = CoroutineScope(Dispatchers.IO + job),
                articleAcquirer = {
                    if (attempts.incrementAndGet() == 2) {
                        retryStarted.complete(Unit)
                        releaseRetry.await()
                    }
                    throw ArticleHttpFailure(503)
                },
                reportImportError = {})
            repository.importUrl("https://failure.invalid/article")
            val task = withTimeout(5000) { repository.errorTasks.first { it.isNotEmpty() }.single() }
            assertEquals("Article HTTP 503", task.errorMessage)
            repository.importUrl(task.url)
            val history = withTimeout(5000) { repository.notifications.first { it.isNotEmpty() } }
            buildNotificationSections(history, listOf(task), emptyList()).let {
                assertEquals(listOf("task:${task.id}"), it.needsAttention.map { row -> row.key })
                assertEquals(1, it.history.size)
            }
            repository.clearAllNotifications()
            withTimeout(5000) { repository.notifications.first { it.isEmpty() } }
            assertNotNull(taskDao.getTaskById(task.id))
            assertEquals(listOf("task:${task.id}"), buildNotificationSections(emptyList(), repository.errorTasks.value, emptyList()).needsAttention.map { it.key })

            repository.retryErrorTaskAndAwait(task.id)
            // Keep the retry in progress until Room publishes the task leaving the error list.
            withTimeout(5000) {
                retryStarted.await()
                repository.errorTasks.first { tasks -> tasks.none { it.id == task.id } }
            }
            releaseRetry.complete(Unit)
            val retriedTask = withTimeout(5000) {
                repository.errorTasks.first { tasks -> tasks.any { it.id == task.id } }
                    .single { it.id == task.id }
            }
            assertEquals("Article HTTP 503", retriedTask.errorMessage)

            repository.deleteErrorTaskAndAwait(task.id)
            assertNull(taskDao.getTaskById(task.id))
            withTimeout(5000) { repository.errorTasks.first { tasks -> tasks.none { it.id == task.id } } }
        } finally { job.cancelAndJoin(); db.close() }
    }

    @Test fun actualXmlHttpFailureProducesDatedFeedRowAndCanRecover(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val feed = RssFeedEntity("xml", "https://xml.invalid/feed", "XML feed")
            db.rssDao().putFeed(feed)
            var status = 404
            val repository = RssRepository(context, db, requestOverride = { url, _, _ ->
                RssHttpPage(status, url, "<rss version=\"2.0\"><channel><title>XML feed</title><link>https://xml.invalid</link><description>Feed</description></channel></rss>")
            }, scheduleChanged = {}, enqueueInitial = { _, _, _ -> })
            repository.fetchAll(0, onlyFeedId = feed.id)
            val failed = requireNotNull(db.rssDao().feed(feed.id))
            assertFalse(failed.enabled)
            assertTrue(failed.error.orEmpty().contains("404"))
            val row = buildNotificationSections(emptyList(), emptyList(), listOf(failed)).needsAttention.single()
            assertEquals(failed.lastAttemptAt, row.createdAt)
            status = 200
            repository.retryXmlError(feed.id)
            val retry = requireNotNull(db.rssDao().feed(feed.id))
            repository.fetchAll(0, onlyFeedId = feed.id, authorizedOperation = retry.acquisitionId)
            val recovered = requireNotNull(db.rssDao().feed(feed.id))
            assertTrue(recovered.enabled)
            assertNull(recovered.error)
            assertTrue(buildNotificationSections(emptyList(), emptyList(), listOf(recovered)).isEmpty)
        } finally { db.close() }
    }
}
