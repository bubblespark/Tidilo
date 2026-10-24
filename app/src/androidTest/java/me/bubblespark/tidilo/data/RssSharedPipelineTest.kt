package me.bubblespark.tidilo.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.config.AppLanguage
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.data.db.RssFeedEntity
import me.bubblespark.tidilo.data.rss.*
import me.bubblespark.tidilo.parser.*
import me.bubblespark.tidilo.service.GeminiTaggingService
import org.junit.Assert.*
import org.junit.Test

class RssSharedPipelineTest {
    @Test fun registrationAndFetchPersistMetadataAndNotModifiedPreservesIt() = runBlocking {
        val db = database()
        var status = 200
        var metadata = "<image><url>https://images.example/icon.png</url></image><link>https://publisher.example/</link>"
        val repo = repository(db, { requested, _, _ ->
            RssHttpPage(status = status, url = requested,
                body = "<rss><channel><title>Feed</title>$metadata</channel></rss>")
        })
        fun saved(): Pair<String?, String?> = db.openHelper.readableDatabase.query("SELECT iconUrl,siteUrl FROM rss_feeds").use {
            assertTrue(it.moveToFirst()); it.getString(0) to it.getString(1)
        }
        try {
            repo.register("https://feed.example/feed", "DIRECT", 30)
            assertEquals("https://images.example/icon.png" to "https://publisher.example/", saved())
            metadata = "<image><url>https://images.example/new.png</url></image><link>https://new.example/</link>"
            repo.fetchAll(1)
            assertEquals("https://images.example/new.png" to "https://new.example/", saved())
            status = 304
            repo.fetchAll(Long.MAX_VALUE)
            assertEquals("https://images.example/new.png" to "https://new.example/", saved())
            status = 200
            metadata = ""
            repo.fetchAll(Long.MAX_VALUE)
            assertEquals(null to null, saved())
        } finally { db.close() }
    }

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val url = "https://fixture-feed.example/post"
    private val html = """<html><head><title>HTML title</title><meta property="og:description" content="HTML description">
        <meta property="og:site_name" content="Publisher"></head>
        <body><article><p>The real article body supplied by the publisher, rather than the short XML excerpt.</p></article></body></html>"""
    private fun xml(count: Int = 1) = "<rss><channel><title>Subscription title</title>" + (1..count).joinToString("") {
        "<item><guid>$it</guid><link>$url/$it</link><title>XML title</title><description>Short XML excerpt</description></item>"
    } + "</channel></rss>"
    private fun database() = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()

    @Test fun explicitRetryKeepsConnectivityWaitLocalWithoutConsumingAttempt() = runBlocking {
        val db = database()
        try {
            for (mode in listOf("AI", "DIRECT")) {
                val feed = RssFeedEntity(mode, "https://fixture-feed.example/$mode", mode, mode = mode)
                db.rssDao().putFeed(feed)
                RssIngestionStore(db).stage(feed, listOf(RssEntry("key", "$url/$mode", "Title", "", "", null, null)), 1)
                val task = db.importTaskDao().getAllTasks().single { it.rssFeedId == feed.id }
                val repo = repository(db, { _, _, _ -> error("Retry must not fetch XML") }, network { throw RssWifiUnavailable() })
                repo.retryAcquisition(task.id)
                val waiting = db.importTaskDao().getTaskById(task.id)!!
                assertEquals("ERROR", waiting.status)
                assertEquals("Waiting for an allowed network", waiting.statusText)
                assertEquals(0, waiting.acquisitionAttempts)
                assertEquals(mode, waiting.intendedMode)
                assertTrue(db.rssDao().feed(feed.id)!!.enabled)
                assertFalse(db.rssDao().hasSeen(feed.id, "key", task.url))
            }
        } finally { db.close() }
    }

    @Test fun explicitRetryStopsRelatedRequestsAfterFeedRevocation() = runBlocking {
        val db = database()
        try {
            val taskUrl = "https://retry-${java.util.UUID.randomUUID()}.example/post"
            val feed = RssFeedEntity("feed", "https://fixture-feed.example/feed", "Feed")
            db.rssDao().putFeed(feed)
            RssIngestionStore(db).stage(feed, listOf(RssEntry("key", taskUrl, "Title", "", "", null, null)), 1)
            val task = db.importTaskDao().getAllTasks().single()
            var relatedRequests = 0
            val repo = repository(db, { _, _, _ -> error("Retry must not fetch XML") }, network { request ->
                if (request == taskUrl) ArticleResponse(request, 200, html.toByteArray(), "text/html")
                else {
                    relatedRequests++
                    val current = db.rssDao().feed(feed.id)!!
                    db.rssDao().putFeed(current.copy(enabled = false, revision = current.revision + 1))
                    ArticleResponse(request, 404, byteArrayOf(), "text/html")
                }
            })
            repo.retryAcquisition(task.id)
            assertEquals(1, relatedRequests)
            assertNotNull(db.bookmarkDao().getBookmarkByUrl(taskUrl))
            assertFalse(db.rssDao().feed(feed.id)!!.enabled)
        } finally { db.close() }
    }

    @Test fun completionHonorsPromotionAndCancellationDuringAcquisition() = runBlocking {
        val db = database()
        try {
            val feed = RssFeedEntity("feed", "https://fixture-feed.example/feed", "Feed", mode = "AI")
            db.rssDao().putFeed(feed)
            val store = RssIngestionStore(db)
            store.stage(feed, RssFeedParser.parse(xml(2), feed.url).entries, 1)
            val tasks = db.importTaskDao().getAllTasks().sortedBy { it.url }
            fun prepared(task: me.bubblespark.tidilo.data.db.ImportTaskEntity) =
                PreparedArticle(task.url, "HTML title", "Publisher", "Description", "Body", null, false)
            db.importTaskDao().updateStatus(tasks[0].id, "WAITING_AI", "Explicitly promoted")
            store.complete(feed, tasks[0], prepared(tasks[0]))
            assertEquals("WAITING_AI", db.importTaskDao().getTaskById(tasks[0].id)!!.status)
            db.importTaskDao().updateStatus(tasks[1].id, "READY", "Canceled while preparing")
            store.complete(feed, tasks[1], prepared(tasks[1]), promote = true)
            assertEquals("READY", db.importTaskDao().getTaskById(tasks[1].id)!!.status)
        } finally { db.close() }
    }

    @Test fun convertingPreparedAiTasksSavesDirectWithoutFetchingOrImmediateRetention() = runBlocking {
        val db = database()
        try {
            val feed = RssFeedEntity("feed", "https://fixture-feed.example/feed", "Feed", mode = "AI", retentionLimit = 30)
            db.rssDao().putFeed(feed)
            repeat(20) { db.bookmarkDao().insertOrUpdate(BookmarkEntity("saved$it", "Keep", "https://fixture-feed.example/saved/$it",
                "", createdAt = it.toLong(), updatedAt = 1, rssFeedId = feed.id)) }
            db.importTaskDao().insertOrUpdate(me.bubblespark.tidilo.data.db.ImportTaskEntity("prepared", url,
                "HTML title", "READY", "Ready", createdAt = 30, rssFeedId = feed.id, articlePrepared = true,
                description = "HTML description", bodySnippet = "Actual HTML body"))
            val repo = repository(db, { _, _, _ -> error("Conversion must not fetch XML") }, network { error("Prepared conversion must not fetch HTML") })
            repo.edit(feed.id, "DIRECT", 10, true)
            assertEquals(21, db.bookmarkDao().getCount())
            assertTrue(db.bookmarkDao().getBookmarkByUrl(url)!!.isAiSkipped)
            assertEquals("HTML description", db.bookmarkDao().getBookmarkByUrl(url)!!.description)
            assertEquals(0, db.importTaskDao().getCount())
        } finally { db.close() }
    }

    @Test fun disablingFeedRevokesInitialPermissionBeforeFurtherRelatedRequests() = runBlocking {
        val db = database()
        try {
            val feed = RssFeedEntity("feed", "https://fixture-feed.example/feed", "Feed",
                acquisitionId = "operation", acquisitionMobileOverride = true)
            db.rssDao().putFeed(feed)
            var requests = 0
            val repo = repository(db, { _, _, _ -> error("No XML expected") }, network {
                requests++
                ArticleResponse(it, 200, html.toByteArray(), "text/html")
            })
            val authorized = repo.articleNetwork("operation", feed = feed)
            repo.setEnabled(feed.id, false)
            try { authorized.fetch(url); fail("Disabled feed must revoke operation permission") }
            catch (_: IllegalStateException) { }
            assertEquals(0, requests)
        } finally { db.close() }
    }

    @Test fun emptyFeedXmlRequestsShareTheWorkerSliceBudget() = runBlocking {
        val db = database()
        try {
            var elapsed = 0L
            var calls = 0
            repeat(3) { db.rssDao().putFeed(RssFeedEntity("feed$it", "https://fixture-feed.example/$it", "Feed")) }
            val repo = RssRepository(context, db, requestOverride = { request, _, _ ->
                calls++
                elapsed += 360_000L
                RssHttpPage(304, request, "")
            }, scheduleChanged = {}, elapsedMillis = { elapsed })
            try { repo.fetchAll(1); fail("Next XML request must yield") }
            catch (_: RssAcquisitionYield) { }
            assertEquals(1, calls)
            assertTrue(db.rssDao().enabledFeeds().size == 3)
        } finally { db.close() }
    }

    @Test fun legacyRunningAndErrorTasksAreOutsideFiveUnstartedSelection() = runBlocking {
        val db = database()
        try {
            val feed = RssFeedEntity("feed", "https://fixture-feed.example/feed", "Feed", mode = "AI")
            db.rssDao().putFeed(feed)
            for (status in listOf("PROCESSING_AI", "ERROR")) {
                db.importTaskDao().insertOrUpdate(me.bubblespark.tidilo.data.db.ImportTaskEntity(status,
                    "$url/$status", "Legacy XML", status, "Legacy", createdAt = 1, rssFeedId = feed.id))
            }
            RssIngestionStore(db).stage(feed, RssFeedParser.parse(xml(6), feed.url).entries, 100)
            assertEquals("PROCESSING_AI", db.importTaskDao().getTaskById("PROCESSING_AI")?.status)
            assertEquals("ERROR", db.importTaskDao().getTaskById("ERROR")?.status)
        } finally { db.close() }
    }

    @Test fun retentionEditDuringArticleFetchDoesNotApplyStaleCycleDeletion() = runBlocking {
        val db = database()
        try {
            val feed = RssFeedEntity("feed", "https://fixture-feed.example/feed", "Feed", retentionLimit = 30)
            db.rssDao().putFeed(feed)
            repeat(20) { db.bookmarkDao().insertOrUpdate(BookmarkEntity("saved$it", "Keep", "https://fixture-feed.example/saved/$it",
                "", createdAt = it.toLong(), updatedAt = 1, rssFeedId = feed.id)) }
            val repo = repository(db, { request, _, _ -> RssHttpPage(200, request, xml()) }, network { request ->
                db.rssDao().putFeed(db.rssDao().feed(feed.id)!!.copy(revision = 1, retentionLimit = 10))
                ArticleResponse(request, 200, html.toByteArray(), "text/html")
            })
            repo.fetchAll(1)
            assertEquals(20, db.bookmarkDao().getCount())
        } finally { db.close() }
    }
    private fun network(action: suspend (String) -> ArticleResponse = {
        if (it.startsWith(url)) ArticleResponse(it, 200, html.toByteArray(), "text/html")
        else throw RssHttpFailure(404)
    }) = object : ArticleNetwork {
        override val scopeKey = "fixture"
        override suspend fun fetch(url: String) = action(url)
    }
    private fun repository(db: AppDatabase, page: suspend (String, Map<String, String>, Boolean) -> RssHttpPage,
        article: ArticleNetwork = network(), enqueue: suspend (String, String, Boolean) -> Unit = { _, _, _ -> }) =
        RssRepository(context, db, page, article, scheduleChanged = {}, enqueueInitial = enqueue)

    @Test fun registrationOnlyValidatesXmlAndLaterDoesNotSkipFirstAcquisition() = runBlocking {
        val db = database()
        try {
            var xmlCalls = 0
            var articleCalls = 0
            val repo = repository(db, { request, _, wifiOnly ->
                xmlCalls++
                if (xmlCalls == 1) assertFalse(wifiOnly)
                RssHttpPage(200, request, xml(), etag = "validator")
            }, network { request ->
                if (request.startsWith(url)) { articleCalls++; ArticleResponse(request, 200, html.toByteArray(), "text/html") }
                else throw RssHttpFailure(404)
            })
            val id = repo.register("https://fixture-feed.example/feed", "DIRECT", 30)
            assertEquals(1, xmlCalls)
            assertEquals(0, articleCalls)
            assertEquals(0, db.bookmarkDao().getCount())
            assertEquals(0, db.importTaskDao().getCount())
            val registered = db.rssDao().feed(id)!!
            assertNull(registered.etag)
            assertNull(registered.lastSuccessAt)
            assertNull(registered.acquisitionId)
            assertTrue(repo.fetchAll(1))
            assertEquals(1, articleCalls)
            val saved = db.bookmarkDao().getBookmarkByUrl("$url/1")!!
            assertEquals("HTML description", saved.description)
            assertEquals("HTML title", saved.title)
            assertTrue(saved.isAiSkipped)
            assertNull(saved.category)
            assertEquals(0, db.importTaskDao().getCount())
        } finally { db.close() }
    }

    @Test fun rssAndManualHtmlProduceIdenticalPreparedPrompt() = runBlocking {
        val db = database()
        try {
            val repo = repository(db, { request, _, _ -> RssHttpPage(200, request, xml()) })
            repo.register("https://fixture-feed.example/feed", "AI", 30)
            assertTrue(repo.fetchAll(1))
            val task = db.importTaskDao().getAllTasks().single()
            assertTrue(task.articlePrepared)
            assertEquals("READY", task.status)
            val manual = ArticlePreparation.prepare(WebPageParser.parseHtml("$url/1", html))
            assertEquals(manual.title, task.title)
            assertEquals(manual.siteName, task.siteName)
            assertEquals(manual.bodySnippet, task.bodySnippet)
            for (language in AppLanguage.entries) {
                assertEquals(GeminiTaggingService.buildPromptWithSnippets(manual.url, manual.siteName,
                    manual.title, manual.description, manual.bodySnippet, language),
                    GeminiTaggingService.buildPromptWithSnippets(task.url, task.siteName, task.title,
                        task.description, task.bodySnippet, language))
            }
        } finally { db.close() }
    }

    @Test fun failedXmlDisablesOnlyAffectedFeedAndKeepsData() = runBlocking {
        val db = database()
        try {
            val bad = RssFeedEntity("bad", "https://fixture-feed.example/bad", "Bad")
            val good = RssFeedEntity("good", "https://fixture-feed.example/good", "Good")
            db.rssDao().putFeed(bad); db.rssDao().putFeed(good)
            db.bookmarkDao().insertOrUpdate(BookmarkEntity("saved", "Keep", url, "Keep note", createdAt = 1,
                updatedAt = 1, rssFeedId = bad.id))
            val repo = repository(db, { request, _, _ ->
                if (request == bad.url) throw IOException("Timeout")
                RssHttpPage(200, request, "<rss><channel><title>Good</title></channel></rss>")
            })
            assertTrue(repo.fetchAll(1))
            assertFalse(db.rssDao().feed(bad.id)!!.enabled)
            assertEquals("XML_FETCH_FAILED", db.rssDao().feed(bad.id)!!.error)
            assertTrue(db.rssDao().feed(good.id)!!.enabled)
            assertEquals(1, db.bookmarkDao().getCount())
            repo.setEnabled(bad.id, true)
            assertEquals("XML_FETCH_FAILED", db.rssDao().feed(bad.id)!!.error)
        } finally { db.close() }
    }

    @Test fun wifiWaitAndCancellationDoNotDisableFeed() = runBlocking {
        val db = database()
        try {
            val feed = RssFeedEntity("feed", "https://fixture-feed.example/feed", "Feed")
            db.rssDao().putFeed(feed)
            for (error in listOf(RssWifiUnavailable(), CancellationException("Stopped"))) {
                val repo = repository(db, { _, _, _ -> throw error })
                try { repo.fetchAll(1); fail("Expected wait/cancellation") } catch (_: RssWifiUnavailable) {
                } catch (_: CancellationException) { }
                assertTrue(db.rssDao().feed(feed.id)!!.enabled)
                assertNull(db.rssDao().feed(feed.id)!!.error)
            }
        } finally { db.close() }
    }

    @Test fun failedArticleRemainsDirectAndRetriesEvenWhenXmlIs304() = runBlocking {
        val db = database()
        try {
            var failArticle = true
            val feed = RssFeedEntity("feed", "https://fixture-feed.example/feed", "Feed")
            db.rssDao().putFeed(feed)
            val page: suspend (String, Map<String, String>, Boolean) -> RssHttpPage = { request, _, _ ->
                RssHttpPage(200, request, xml(), etag = "etag")
            }
            val first = repository(db, page, network { request ->
                if (request.startsWith(url)) {
                    if (failArticle) throw IOException("Timed out")
                    ArticleResponse(request, 200, html.toByteArray(), "text/html")
                } else throw RssHttpFailure(404)
            })
            assertFalse(first.fetchAll(1))
            val failed = db.importTaskDao().getAllTasks().single()
            assertEquals("DIRECT", failed.intendedMode)
            assertEquals("ERROR", failed.status)
            assertFalse(db.rssDao().hasSeen(feed.id, failed.rssItemKey!!, failed.url))
            assertTrue(db.rssDao().feed(feed.id)!!.enabled)
            // Emulate a new operation after restart with a feed that no longer includes this article.
            val current = db.rssDao().feed(feed.id)!!
            db.rssDao().putFeed(current.copy(acquisitionId = null, acquisitionXmlDone = false))
            db.importTaskDao().insertOrUpdate(failed.copy(acquisitionRetryAt = 0))
            failArticle = false
            val restarted = repository(db, { request, _, _ -> RssHttpPage(304, request, "") }, network { request ->
                if (request.startsWith(url)) ArticleResponse(request, 200, html.toByteArray(), "text/html") else throw RssHttpFailure(404)
            })
            assertTrue(restarted.fetchAll(2))
            assertTrue(db.bookmarkDao().getBookmarkByUrl(failed.url)!!.isAiSkipped)
            assertEquals(0, db.importTaskDao().getCount())
        } finally { db.close() }
    }

    @Test fun partialDirectAcquisitionHasNoCountCapAndDoesNotDisableFeed() = runBlocking {
        val db = database()
        try {
            val feed = RssFeedEntity("feed", "https://fixture-feed.example/feed", "Feed", retentionLimit = null)
            db.rssDao().putFeed(feed)
            val repo = repository(db, { request, _, _ -> RssHttpPage(200, request, xml(24)) }, network { request ->
                if (request == "$url/7") throw RssHttpFailure(404)
                if (request.startsWith(url)) ArticleResponse(request, 200, html.toByteArray(), "text/html") else throw RssHttpFailure(404)
            })
            assertTrue(repo.fetchAll(1))
            assertEquals(23, db.bookmarkDao().getCount())
            assertEquals("DIRECT", db.importTaskDao().getAllTasks().single().intendedMode)
            assertTrue(db.rssDao().feed(feed.id)!!.enabled)
        } finally { db.close() }
    }

    @Test fun staleXmlFailureCannotDisableRevisedFeedAndArticleFailureDoesNotConsume() = runBlocking {
        val db = database()
        try {
            val feed = RssFeedEntity("feed", "https://fixture-feed.example/feed", "Feed")
            val store = RssIngestionStore(db)
            db.rssDao().putFeed(feed)
            db.rssDao().putFeed(feed.copy(revision = 1))
            assertFalse(store.disableAfterXmlFailure(feed))
            assertTrue(db.rssDao().feed(feed.id)!!.enabled)
            val current = db.rssDao().feed(feed.id)!!
            assertTrue(store.stage(current, RssFeedParser.parse(xml(), feed.url).entries, 1))
            val staged = db.importTaskDao().getAllTasks().single()
            assertEquals(0, db.bookmarkDao().getCount())
            assertFalse(db.rssDao().hasSeen(feed.id, staged.rssItemKey!!, staged.url))
            assertEquals(0, db.importTaskDao().claimForAi(staged.id, "Must not analyze"))
        } finally { db.close() }
    }

    @Test fun initialPermissionPersistsOnlyOnItsOperationAndReenableClearsIt() = runBlocking {
        val db = database()
        try {
            val feed = RssFeedEntity("feed", "https://fixture-feed.example/feed", "Feed")
            db.rssDao().putFeed(feed)
            val queued = mutableListOf<Triple<String, String, Boolean>>()
            val repo = repository(db, { _, _, _ -> error("No XML validation during scheduling") },
                enqueue = { id, operation, mobile -> queued.add(Triple(id, operation, mobile)) })
            repo.requestInitialFetch(feed.id, true)
            val operation = db.rssDao().feed(feed.id)!!
            assertTrue(operation.acquisitionMobileOverride)
            assertEquals(operation.acquisitionId, queued.single().second)
            repo.setEnabled(feed.id, false)
            assertNull(db.rssDao().feed(feed.id)!!.acquisitionId)
            assertFalse(db.rssDao().feed(feed.id)!!.acquisitionMobileOverride)
        } finally { db.close() }
    }
}
