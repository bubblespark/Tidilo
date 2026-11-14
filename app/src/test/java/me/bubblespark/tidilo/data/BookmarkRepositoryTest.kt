package me.bubblespark.tidilo.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.config.AppLanguage
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.data.db.BookmarkEntityOccurrence
import me.bubblespark.tidilo.data.db.BookmarkEntityTagUpdate
import me.bubblespark.tidilo.data.db.BookmarkEntityTags
import me.bubblespark.tidilo.data.db.TrashedBookmarkEntity
import me.bubblespark.tidilo.service.GeminiTaggingService
import me.bubblespark.tidilo.ui.screens.library.BookmarkItem
import me.bubblespark.tidilo.ui.screens.library.Tag
import me.bubblespark.tidilo.ui.screens.library.TrashedBookmarkItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookmarkRepositoryTest {

    private val defaultTag = Tag.create("Article", "Tech", listOf("Test"))

    @Test
    fun skippedBookmarkPreservesEachNullableAxisThroughBackupAndRoomMapping() {
        for (tag in listOf(Tag("Article", null), Tag(null, "Food"), Tag(null, null, listOf("Saved")))) {
            val original = BookmarkItem("partial", "Page", "https://example.com", "", tag)
            val restored = BookmarkRepository.parseBookmarkFromObj(BookmarkRepository.serializeBookmarkToObj(original))
            val fromRoom = me.bubblespark.tidilo.data.db.BookmarkEntity.fromBookmarkItem(restored).toBookmarkItem()
            assertEquals(tag, fromRoom.tag)
            assertFalse(BookmarkRepository.serializeBookmarkToObj(original).has("isAiSkipped"))
        }
    }

    @Test
    fun backupParsingDistinguishesExplicitBlankFromNullCategoryAxes() {
        val jsonWithNull = org.json.JSONObject("{\"id\":\"1\",\"title\":\"T\",\"url\":\"https://example.com\",\"description\":\"\",\"tag\":{\"category\":null,\"subcategory\":null}}")
        val restoredNull = BookmarkRepository.parseBookmarkFromObj(jsonWithNull)
        org.junit.Assert.assertNull(restoredNull.tag.category)
        org.junit.Assert.assertNull(restoredNull.tag.subcategory)

        val jsonWithBlank = org.json.JSONObject("{\"id\":\"2\",\"title\":\"T\",\"url\":\"https://example.com\",\"description\":\"\",\"tag\":{\"category\":\"   \",\"subcategory\":\"\"}}")
        val restoredBlank = BookmarkRepository.parseBookmarkFromObj(jsonWithBlank)
        assertEquals("None", restoredBlank.tag.category)
        assertEquals("None", restoredBlank.tag.subcategory)
    }

    @Test
    fun legacyListParserPreservesSerializedCanonicalHash() {
        val original = BookmarkItem("legacy-hash", "Hash", "https://example.com/hash", "", Tag("Tool", "Tech", listOf("#C", "C")))
        val restored = BookmarkRepository.parseBookmarks(BookmarkRepository.serializeBookmarks(listOf(original))).single()
        assertEquals(listOf("#C", "C"), restored.tag.entities)
    }

    @Test
    fun fallbackSiteNameIsCleanedExactlyOnceBeforeDisplayTagConstruction() {
        val parsed = me.bubblespark.tidilo.parser.WebPageParser.parseHtml("https://example.com", """<html><head><meta property="og:site_name" content=" ##C "></head><body>Body</body></html>""")
        val result = GeminiTaggingService.parseAiResponse("Invalid JSON", "Title", parsed)
        assertEquals(listOf("#C"), Tag.fromDisplayValues(result.category, result.subcategory, result.entities).entities)
    }

    @Test
    fun serializedCanonicalHashAndPlainCRemainDistinct() {
        val original = BookmarkItem("hash", "Hash", "https://example.com/hash", "", Tag("Tool", "Tech", listOf("#C", "C")))
        val restored = BookmarkRepository.parseBookmarkFromObj(BookmarkRepository.serializeBookmarkToObj(original))
        assertEquals(listOf("#C", "C"), restored.tag.entities)
    }

    @Test
    fun aiParserDeduplicatesKeysBeforeTenEntityLimit() {
        val result = GeminiTaggingService.parseAiResponse(
            rawOutput = """{"summary":"Summary","category":"Tool","subcategory":"Tech","entities":["GitHub","git-hub","git_hub","git.hub","Git Hub","A","B","C","D","E","F","G","H","I"]}""",
            fallbackTitle = "Title", parsedWebPage = null)
        assertEquals(listOf("GitHub", "A", "B", "C", "D", "E", "F", "G", "H", "I"), result.entities)
    }

    @Test
    fun testTrashRetentionAndCapacityConstants() {
        assertEquals(30L, BookmarkRepository.TRASH_RETENTION_DAYS)
        assertEquals(30L * 24 * 60 * 60 * 1000L, BookmarkRepository.TRASH_RETENTION_MS)
        assertEquals(100, BookmarkRepository.MAX_TRASH_CAPACITY)
    }

    @Test
    fun testTrashSerializationAndDeserialization() {
        val now = 1791650202300L
        val originalBookmark = BookmarkItem(
            id = "trash-1",
            title = "Jetpack Compose Guides",
            url = "https://developer.android.com/compose",
            description = "Android modern UI toolkit",
            tag = Tag.create("Article", "Tech", listOf("Compose", "Android")),
            createdAt = now - 100000,
            updatedAt = now - 50000,
            note = "Important note to keep"
        )
        val trashedItem = TrashedBookmarkItem(
            bookmark = originalBookmark,
            trashedAt = now
        )

        // 1. Serialize
        val json = BookmarkRepository.serializeTrash(listOf(trashedItem))
        assertTrue("JSON must contain trashedAt", json.contains("\"trashedAt\":$now"))
        assertTrue("JSON must contain bookmark title", json.contains("Jetpack Compose Guides"))

        // 2. Deserialize
        val parsedList = BookmarkRepository.parseTrash(json)
        assertEquals(1, parsedList.size)
        val restored = parsedList[0]

        assertEquals(now, restored.trashedAt)
        assertEquals("trash-1", restored.bookmark.id)
        assertEquals("Jetpack Compose Guides", restored.bookmark.title)
        assertEquals("Article", restored.bookmark.tag.category)
        assertEquals("Tech", restored.bookmark.tag.subcategory)
        assertEquals(listOf("Compose", "Android"), restored.bookmark.tag.entities)
        assertEquals("Important note to keep", restored.bookmark.note)
    }

    @Test
    fun testTrashRetentionExpiryFilteringLogic() {
        val now = System.currentTimeMillis()
        val within30DaysTimestamp = now - (29L * 24 * 60 * 60 * 1000L) // 29 days elapsed (retained)
        val expiredTimestamp = now - (31L * 24 * 60 * 60 * 1000L)      // 31 days elapsed (expired)

        val bookmark1 = BookmarkItem(id = "valid", title = "Valid", url = "https://example.com/1", description = "", tag = defaultTag)
        val bookmark2 = BookmarkItem(id = "expired", title = "Expired", url = "https://example.com/2", description = "", tag = defaultTag)

        val trashList = listOf(
            TrashedBookmarkItem(bookmark1, within30DaysTimestamp),
            TrashedBookmarkItem(bookmark2, expiredTimestamp)
        )

        // Verify 30-day expiration logic (BookmarkRepository.purgeExpiredTrash)
        val validTrash = trashList.filter { (now - it.trashedAt) < BookmarkRepository.TRASH_RETENTION_MS }

        assertEquals(1, validTrash.size)
        assertEquals("valid", validTrash[0].bookmark.id)
    }

    @Test
    fun testTrashCapacityOverflowPrunesOldest() {
        val now = System.currentTimeMillis()
        // Enqueue 105 items into trash
        val items = (1..105).map { i ->
            val bookmark = BookmarkItem(id = "b-$i", title = "Item $i", url = "https://example.com/$i", description = "", tag = defaultTag)
            // Larger i corresponds to newer timestamp
            TrashedBookmarkItem(bookmark, trashedAt = now + (i * 1000L))
        }

        // Capacity limit logic in BookmarkRepository.moveToTrash:
        // sortedByDescending { it.trashedAt }.take(MAX_TRASH_CAPACITY)
        val prunedTrash = items
            .sortedByDescending { it.trashedAt }
            .take(BookmarkRepository.MAX_TRASH_CAPACITY)

        assertEquals(100, prunedTrash.size)
        // Newest item (b-105) at the top
        assertEquals("b-105", prunedTrash.first().bookmark.id)
        // 100th item is b-6
        assertEquals("b-6", prunedTrash.last().bookmark.id)
        // Verify the oldest 5 items (b-1 through b-5) were purged permanently
        val remainingIds = prunedTrash.map { it.bookmark.id }.toSet()
        assertFalse(remainingIds.contains("b-1"))
        assertFalse(remainingIds.contains("b-2"))
        assertFalse(remainingIds.contains("b-3"))
        assertFalse(remainingIds.contains("b-4"))
        assertFalse(remainingIds.contains("b-5"))
    }

    @Test
    fun testGeminiTaggingPromptSummaryInstructionFocusesOnPageIntroduction() {
        val promptJa = GeminiTaggingService.buildPrompt(
            url = "https://developer.android.com/compose",
            parsed = null,
            language = AppLanguage.JAPANESE
        )
        assertTrue(
            "Prompt should contain web page introduction focus instruction",
            promptJa.contains("Focus on what this web page introduces, explains, or provides, NOT the dictionary definition of the subject.")
        )

        val promptEn = GeminiTaggingService.buildPrompt(
            url = "https://developer.android.com/compose",
            parsed = null,
            language = AppLanguage.ENGLISH
        )
        assertTrue(
            "Prompt should contain web page introduction focus instruction",
            promptEn.contains("Focus on what this web page introduces, explains, or provides, NOT the dictionary definition of the subject.")
        )
    }

    @Test
    fun testGeminiTaggingPromptContainsDetailedSubcategoryRules() {
        val prompt = GeminiTaggingService.buildPrompt(
            url = "https://developer.android.com/compose",
            parsed = null,
            language = AppLanguage.JAPANESE
        )
        val expectedSubcategories = listOf(
            "- Tech: Software, hardware, AI, programming, IT gadgets, engineering, internet infrastructure.",
            "- Business: Startups, finance, marketing, corporate management, careers, economy, B2B services.",
            "- Society: News, politics, law, environmental issues, social phenomena, culture, public affairs.",
            "- Life: Daily living, housing, health, wellness, relationships, parenting, personal finance, self-care.",
            "- Food: Recipes, cooking, restaurants, gourmet reviews, beverages, food culture, dining guides.",
            "- Travel: Tourism, local guides, hotels, transport, outdoor adventures, destination tips, sightseeing.",
            "- Game: Video games, board games, esports, gameplay guides, gaming news, game development.",
            "- Entertainment: Movies, TV shows, anime, manga, pop music, celebrity news, theater, humor.",
            "- Hobby: Crafts, photography, DIY, fitness, collecting, pet care, cars, personal creative pursuits.",
            "- Adult: NSFW content, pornography, mature dating services, erotic illustrations, age-restricted themes (18+).",
            "- Education: Academic subjects, language learning, tutorials, study resources, qualifications, courses."
        )

        for (subcategoryRule in expectedSubcategories) {
            assertTrue(
                "Prompt should contain subcategory rule: $subcategoryRule",
                prompt.contains(subcategoryRule)
            )
        }
    }

    @Test
    fun testGeminiTaggingPromptContainsUpdatedEntitiesTagRules() {
        val prompt = GeminiTaggingService.buildPrompt(
            url = "https://developer.android.com/compose",
            parsed = null,
            language = AppLanguage.JAPANESE
        )
        assertTrue(
            "Prompt should specify extracting between 2 to 10 entities",
            prompt.contains("Extract between 2 to 10 specific noun keywords")
        )
    }

    @Test
    fun testGeminiTaggingServiceParseAiResponseSupportsUpTo10Entities() {
        val rawAiOutput = """
        {
          "summary": "Jetpack Compose is Android's modern toolkit for building native UI.",
          "category": "Tool",
          "subcategory": "Tech",
          "entities": ["Compose", "Kotlin", "Android", "UI", "Google", "Jetpack", "Declarative", "Compiler", "Material3", "Canvas", "Extra11"]
        }
        """.trimIndent()

        val result = GeminiTaggingService.parseAiResponse(
            rawOutput = rawAiOutput,
            fallbackTitle = "Jetpack Compose",
            parsedWebPage = null
        )

        assertEquals("Tool", result.category)
        assertEquals("Tech", result.subcategory)
        assertEquals(10, result.entities.size)
        assertEquals(
            listOf("Compose", "Kotlin", "Android", "UI", "Google", "Jetpack", "Declarative", "Compiler", "Material3", "Canvas"),
            result.entities
        )
    }

    @Test
    fun testBookmarkItemIsPrivateSerializationAndDeserialization() {
        val privateBookmark = BookmarkItem(
            id = "private-1",
            title = "Adult Creative Work",
            url = "https://example.com/r18/item",
            description = "18+ content description",
            tag = Tag.create("Media", "Adult", listOf("Sample")),
            isPrivate = true
        )

        val json = BookmarkRepository.serializeBookmarkToObj(privateBookmark)
        assertTrue(json.optBoolean("isPrivate"))

        val restored = BookmarkRepository.parseBookmarkFromObj(json)
        assertTrue(restored.isPrivate)
        assertEquals("Adult Creative Work", restored.title)

        // Legacy fallback: old JSON with isNsfw=true still parses into isPrivate=true
        val legacyJson = org.json.JSONObject(json.toString()).apply {
            remove("isPrivate")
            put("isNsfw", true)
        }
        assertTrue(BookmarkRepository.parseBookmarkFromObj(legacyJson).isPrivate)

        // Backward compatibility: old JSON without isPrivate/isNsfw field defaults to false
        json.remove("isPrivate")
        val backwardCompat = BookmarkRepository.parseBookmarkFromObj(json)
        assertFalse(backwardCompat.isPrivate)
    }

    @Test
    fun testBookmarkListIsPrivateSerializationAndDeserialization() {
        val mixedList = listOf(
            BookmarkItem(
                id = "clean-1",
                title = "Clean Tech",
                url = "https://example.com/tech",
                description = "Tech article",
                tag = Tag.create("Tech", "Tech"),
                isPrivate = false
            ),
            BookmarkItem(
                id = "private-1",
                title = "Adult Artwork",
                url = "https://pixiv.net/artworks/123",
                description = "R-18 artwork",
                tag = Tag.create("Media", "Adult"),
                isPrivate = true
            )
        )

        // Serialize full list to JSON string (same as saved in SharedPreferences)
        val serializedJson = BookmarkRepository.serializeBookmarks(mixedList)
        assertTrue(serializedJson.contains("\"isPrivate\":true"))

        // Parse list back (same as loadFromPrefs on screen reload/app restart)
        val parsedList = BookmarkRepository.parseBookmarks(serializedJson)
        assertEquals(2, parsedList.size)
        assertFalse("clean-1 should retain isPrivate = false", parsedList[0].isPrivate)
        assertTrue("private-1 MUST retain isPrivate = true across list deserialization", parsedList[1].isPrivate)
    }

    @Test
    fun testAdultSubcategoryJapaneseLabelIs大人の嗜好() {
        assertEquals("大人の嗜好", Subcategory.ADULT.jaLabel)
        assertEquals("Adult", Subcategory.ADULT.enLabel)
        assertEquals(11, Subcategory.entries.size)
    }

    @Test
    fun testDomainPolicyStoreRegistrationAndToggling() = runBlocking {
        val policyDao = InMemoryDomainPolicyDao()
        val bookmarkDao = InMemoryBookmarkDao()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val store = DomainPolicyStore(
                database = fakeAppDatabase(bookmarkDao = bookmarkDao, domainPolicyDao = policyDao),
                transactionRunner = directTransactionRunner(),
                storeScope = scope
            )

            // 1. Initial registration defaults to false
            store.registerDomainAndAwait("pixiv.net")
            assertFalse(store.isPrivateDomain("pixiv.net"))

            // 2. Set Private flag to true
            store.setDomainPrivateAndAwait("pixiv.net", true)
            assertTrue(store.isPrivateDomain("pixiv.net"))

            // 3. Sync domain does not overwrite existing true flag
            store.syncDomainsAndAwait(listOf("pixiv.net", "dlsite.com"))
            assertTrue(store.isPrivateDomain("pixiv.net"))
            assertFalse(store.isPrivateDomain("dlsite.com"))

            // 4. Listing policies
            val policies = store.getAllPolicies()
            assertEquals(2, policies.size)
            assertEquals("dlsite.com", policies[0].domain)
            assertFalse(policies[0].isPrivate)
            assertEquals("pixiv.net", policies[1].domain)
            assertTrue(policies[1].isPrivate)
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test
    fun testDomainPolicyStoreDisplayName() = runBlocking {
        val policyDao = InMemoryDomainPolicyDao()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val store = DomainPolicyStore(
                database = fakeAppDatabase(domainPolicyDao = policyDao),
                transactionRunner = directTransactionRunner(),
                storeScope = scope
            )

            // 1. Register with displayName
            store.registerDomainAndAwait("github.com", false, "GitHub")
            assertEquals("GitHub", store.getDomainDisplayName("github.com"))
            assertEquals("GitHub", store.getDisplayName("github.com"))

            // 2. Register without displayName falls back to domain
            store.registerDomainAndAwait("example.com", false)
            assertEquals("", store.getDomainDisplayName("example.com"))
            assertEquals("example.com", store.getDisplayName("example.com"))

            // 3. Update displayName
            store.setDomainDisplayNameAndAwait("example.com", "Example Site")
            assertEquals("Example Site", store.getDisplayName("example.com"))
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test
    fun testDomainGroupItemSortingByDisplayName() {
        val items = listOf(
            me.bubblespark.tidilo.ui.screens.library.DomainGroupItem("pixiv.net", "Pixiv", 5),
            me.bubblespark.tidilo.ui.screens.library.DomainGroupItem("nintendo.co.jp", "任天堂", 3),
            me.bubblespark.tidilo.ui.screens.library.DomainGroupItem("amazon.co.jp", "Amazon", 10),
            me.bubblespark.tidilo.ui.screens.library.DomainGroupItem("hatena.ne.jp", "はてな", 8),
            me.bubblespark.tidilo.ui.screens.library.DomainGroupItem("github.com", "GitHub", 12)
        )

        val collator = java.text.Collator.getInstance(java.util.Locale.JAPANESE)
        val sorted = items.sortedWith { a, b ->
            val isAsciiA = a.displayName.isNotEmpty() && a.displayName[0].code < 128
            val isAsciiB = b.displayName.isNotEmpty() && b.displayName[0].code < 128
            if (isAsciiA && !isAsciiB) {
                -1
            } else if (!isAsciiA && isAsciiB) {
                1
            } else {
                collator.compare(a.displayName, b.displayName)
            }
        }

        val names = sorted.map { it.displayName }
        // A-Z first: Amazon, GitHub, Pixiv, then non-ASCII Japanese names (or collation order)
        assertEquals("Amazon", names[0])
        assertEquals("GitHub", names[1])
        assertEquals("Pixiv", names[2])
        // Non-ASCII items follow
        assertTrue(names[3] == "はてな" || names[3] == "任天堂")
        assertTrue(names[4] == "はてな" || names[4] == "任天堂")
    }

    @Test
    fun testUpdateBookmarkFieldsUpdatesSummaryAndTag() {
        val initialItem = BookmarkItem(
            id = "test-123",
            title = "Sample Title",
            url = "https://example.com",
            description = "Initial description",
            tag = Tag.create("Article", "Tech", listOf("Kotlin")),
            note = "My personal note"
        )

        // 1. Update description only
        val updatedDesc = initialItem.copy(
            description = "New AI generated summary",
            updatedAt = System.currentTimeMillis()
        )
        assertEquals("New AI generated summary", updatedDesc.description)
        assertEquals(initialItem.tag, updatedDesc.tag)
        assertEquals("My personal note", updatedDesc.note)

        // 2. Update tag only
        val newTag = Tag.create("Tool", "Tech", listOf("Android", "Compose"))
        val updatedTag = initialItem.copy(
            tag = newTag,
            updatedAt = System.currentTimeMillis()
        )
        assertEquals("Initial description", updatedTag.description)
        assertEquals("Tool", updatedTag.tag.category)
        assertEquals(listOf("Android", "Compose"), updatedTag.tag.entities)

        // 3. Update both description and tag while preserving note and other fields
        val updatedBoth = initialItem.copy(
            description = "Updated both",
            tag = newTag,
            updatedAt = System.currentTimeMillis()
        )
        assertEquals("Updated both", updatedBoth.description)
        assertEquals(newTag, updatedBoth.tag)
        assertEquals("My personal note", updatedBoth.note)
    }

    private class InMemoryBookmarkDao : StubBookmarkDao() {
        val rows = linkedMapOf<String, BookmarkEntity>()
        val occurrences = linkedMapOf<String, List<BookmarkEntityOccurrence>>()

        override suspend fun getBookmarkById(id: String): BookmarkEntity? = rows[id]
        override suspend fun getBookmarkByUrl(url: String): BookmarkEntity? = rows.values.firstOrNull { it.url == url }
        override suspend fun insertRow(bookmark: BookmarkEntity) { rows[bookmark.id] = bookmark }
        override suspend fun insertRows(bookmarks: List<BookmarkEntity>) { bookmarks.forEach { rows[it.id] = it } }
        override suspend fun insertOccurrences(rows: List<BookmarkEntityOccurrence>) {
            rows.groupBy { it.bookmarkId }.forEach { (id, list) ->
                occurrences[id] = (occurrences[id].orEmpty() + list).sortedBy { it.position }
            }
        }
        override suspend fun deleteOccurrences(id: String) { occurrences.remove(id) }
        override suspend fun deleteOccurrencesForBookmarks(bookmarkIds: List<String>) {
            bookmarkIds.forEach { occurrences.remove(it) }
        }
        override suspend fun canonicalEntityValue(skeletonKey: String): String? =
            occurrences.values.flatten().firstOrNull { entitySkeletonKey(it.value) == skeletonKey }?.value
        override suspend fun entityRowsForReplacement(skeletonKey: String, afterId: String?): List<BookmarkEntityTags> =
            rows.values
                .filter { row -> occurrences[row.id].orEmpty().any { entitySkeletonKey(it.value) == skeletonKey } }
                .filter { afterId == null || it.id > afterId }
                .sortedBy { it.id }
                .take(500)
                .map { BookmarkEntityTags(it.id, it.entities) }
        override suspend fun updateEntityRows(rows: List<BookmarkEntityTagUpdate>) {
            rows.forEach { update ->
                val current = this.rows[update.id] ?: return@forEach
                this.rows[update.id] = current.copy(entities = update.entities, updatedAt = update.updatedAt)
            }
        }
        override suspend fun deleteById(id: String) {
            rows.remove(id)
            occurrences.remove(id)
        }
        override suspend fun deleteByIdOrUrl(id: String, url: String) {
            val removed = rows.values.filter { it.id == id || it.url == url }.map { it.id }
            removed.forEach {
                rows.remove(it)
                occurrences.remove(it)
            }
        }
        override suspend fun getCount(): Int = rows.size
        override suspend fun deleteAll() {
            rows.clear()
            occurrences.clear()
        }
        override suspend fun updateDomainPrivate(domain: String, isPrivate: Boolean) {
            rows.replaceAll { _, entity ->
                if (entity.domain == domain) entity.copy(isPrivate = isPrivate) else entity
            }
        }
    }

    private class InMemoryTrashDao : StubTrashDao() {
        val rows = linkedMapOf<String, TrashedBookmarkEntity>()
        val conflictingUrls = mutableSetOf<String>()
        override suspend fun getById(id: String): TrashedBookmarkEntity? = rows[id]
        override suspend fun deleteByIdCounted(id: String): Int = if (rows.remove(id) != null) 1 else 0
        override suspend fun hasConflictingImport(url: String): Boolean = url in conflictingUrls
        override suspend fun getAllTrash(): List<TrashedBookmarkEntity> = rows.values.sortedByDescending { it.trashedAt }
        override suspend fun insert(item: TrashedBookmarkEntity) { rows[item.id] = item }
        override suspend fun insertAll(items: List<TrashedBookmarkEntity>) { items.forEach { rows[it.id] = it } }
        override suspend fun deleteById(id: String) { rows.remove(id) }
        override suspend fun enforceCapacity(maxCapacity: Int) {
            val keep = rows.values.sortedByDescending { it.trashedAt }.take(maxCapacity).map { it.id }.toSet()
            rows.keys.retainAll(keep)
        }
        override suspend fun getCount(): Int = rows.size
        override suspend fun deleteAll() { rows.clear() }
    }

    private fun testRepository(
        bookmarkDao: InMemoryBookmarkDao,
        trashDao: InMemoryTrashDao,
        scope: CoroutineScope,
        transactionRunner: TransactionRunner = directTransactionRunner(),
        domainPolicyStore: DomainPolicyStore? = null
    ): BookmarkRepository = BookmarkRepository(
        database = fakeAppDatabase(bookmarkDao = bookmarkDao, trashDao = trashDao),
        transactionRunner = transactionRunner,
        repositoryScope = scope,
        autoInitialize = false,
        domainPolicyStore = domainPolicyStore
    )

    @Test
    fun moveToTrashMovesRowClearsLikeAndEnforcesCapacity() = runBlocking {
        val bookmarkDao = InMemoryBookmarkDao()
        val trashDao = InMemoryTrashDao()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val repository = testRepository(bookmarkDao, trashDao, scope)
            bookmarkDao.rows["b1"] = BookmarkEntity(
                id = "b1", title = "First", url = "https://example.com/1", description = "Desc",
                createdAt = 1L, updatedAt = 1L, isLiked = true
            )
            bookmarkDao.rows["b2"] = BookmarkEntity(
                id = "b2", title = "Second", url = "https://example.com/2", description = "Desc",
                createdAt = 2L, updatedAt = 2L, isLiked = false
            )

            assertFalse(repository.moveToTrash("missing", now = 10L, capacity = 1))
            assertTrue(repository.moveToTrash("b1", now = 10L, capacity = 1))
            assertNull(bookmarkDao.rows["b1"])
            assertFalse(trashDao.rows.getValue("b1").isLiked)

            assertTrue(repository.moveToTrash("b2", now = 20L, capacity = 1))
            assertEquals(setOf("b2"), trashDao.rows.keys)
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test
    fun restoreFromTrashCoversAllGuardAndSuccessOutcomes() = runBlocking {
        val bookmarkDao = InMemoryBookmarkDao()
        val trashDao = InMemoryTrashDao()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val repository = testRepository(bookmarkDao, trashDao, scope)
            assertEquals(TrashMutationResult.Missing, repository.restoreFromTrash("absent"))

            val trashed = TrashedBookmarkEntity(
                id = "t1", title = "Saved", url = "https://example.com/saved", description = "Summary",
                category = "Article", subcategory = "Tech", entities = listOf("Kotlin"),
                createdAt = 1L, updatedAt = 1L, note = "memo", isPrivate = false, isLiked = true, trashedAt = 10L
            )
            trashDao.rows["t1"] = trashed

            bookmarkDao.rows["other"] = BookmarkEntity(
                id = "other", title = "Other", url = "https://example.com/saved", description = "",
                createdAt = 1L, updatedAt = 1L
            )
            assertEquals(TrashMutationResult.DuplicateInLibrary, repository.restoreFromTrash("t1"))
            bookmarkDao.rows.clear()

            bookmarkDao.rows["t1"] = BookmarkEntity(
                id = "t1", title = "SameId", url = "https://example.com/different", description = "",
                createdAt = 1L, updatedAt = 1L
            )
            assertEquals(TrashMutationResult.IdentityConflict, repository.restoreFromTrash("t1"))
            bookmarkDao.rows.clear()

            trashDao.conflictingUrls += "https://example.com/saved"
            assertEquals(TrashMutationResult.ImportPending, repository.restoreFromTrash("t1"))
            trashDao.conflictingUrls.clear()

            assertEquals(TrashMutationResult.Restored, repository.restoreFromTrash("t1"))
            assertNull(trashDao.rows["t1"])
            val restored = bookmarkDao.rows["t1"]
            assertEquals("memo", restored?.note)
            assertFalse(restored?.isLiked ?: true)
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test
    fun deleteFromTrashReturnsDeletedOrMissing() = runBlocking {
        val bookmarkDao = InMemoryBookmarkDao()
        val trashDao = InMemoryTrashDao()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val repository = testRepository(bookmarkDao, trashDao, scope)
            trashDao.rows["t1"] = TrashedBookmarkEntity(
                id = "t1", title = "Saved", url = "https://example.com/1", description = "",
                createdAt = 1L, updatedAt = 1L, note = "", isPrivate = false, trashedAt = 10L
            )
            assertEquals(TrashMutationResult.Deleted, repository.deleteFromTrash("t1"))
            assertNull(trashDao.rows["t1"])
            assertEquals(TrashMutationResult.Missing, repository.deleteFromTrash("t1"))
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test
    fun updateBookmarkDetailsReplacesSpellingsAndSyncsOccurrences() = runBlocking {
        val bookmarkDao = InMemoryBookmarkDao()
        val trashDao = InMemoryTrashDao()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val repository = testRepository(bookmarkDao, trashDao, scope)
            bookmarkDao.insertAll(
                listOf(
                    BookmarkEntity(
                        id = "b1", title = "One", url = "https://example.com/1", description = "Desc 1",
                        category = "Article", subcategory = "Tech", entities = listOf("GitHub"),
                        createdAt = 1L, updatedAt = 1L
                    ),
                    BookmarkEntity(
                        id = "b2", title = "Two", url = "https://example.com/2", description = "Desc 2",
                        category = "Article", subcategory = "Tech", entities = listOf("GitHub"),
                        createdAt = 2L, updatedAt = 2L
                    )
                )
            )

            repository.updateBookmarkDetails(
                id = "b1",
                note = "Updated note",
                tag = Tag("Tool", "Tech", listOf("GitHub", "Android")),
                isPrivate = true,
                replacements = mapOf("github" to "git-hub"),
                updatedAt = 99L
            )

            val b1 = bookmarkDao.rows.getValue("b1")
            assertEquals("Updated note", b1.note)
            assertEquals("Tool", b1.category)
            assertTrue(b1.isPrivate)
            assertEquals(99L, b1.updatedAt)
            assertEquals(listOf("git-hub", "Android"), b1.entities)
            assertEquals(listOf("git-hub", "Android"), bookmarkDao.occurrences.getValue("b1").map { it.value })
            assertEquals(listOf("git-hub"), bookmarkDao.rows.getValue("b2").entities)
            assertEquals(listOf("git-hub"), bookmarkDao.occurrences.getValue("b2").map { it.value })

            val missingError = runCatching {
                repository.updateBookmarkDetails("missing", "", Tag(), false, updatedAt = 100L)
            }.exceptionOrNull()
            assertTrue(missingError is IllegalArgumentException)

            var transactionCount = 0
            val rollbackRepository = testRepository(
                bookmarkDao,
                trashDao,
                scope,
                transactionRunner = object : TransactionRunner {
                    override suspend fun <R> run(block: suspend () -> R): R {
                        transactionCount++
                        val rowsSnapshot = LinkedHashMap(bookmarkDao.rows)
                        val occurrencesSnapshot = LinkedHashMap(bookmarkDao.occurrences)
                        return try {
                            block()
                        } catch (t: Throwable) {
                            bookmarkDao.rows.clear()
                            bookmarkDao.rows.putAll(rowsSnapshot)
                            bookmarkDao.occurrences.clear()
                            bookmarkDao.occurrences.putAll(occurrencesSnapshot)
                            throw t
                        }
                    }
                }
            )
            val invalidReplacement = runCatching {
                rollbackRepository.updateBookmarkDetails(
                    id = "b1",
                    note = "Should rollback",
                    tag = Tag("Tool", "Tech", listOf("git-hub")),
                    isPrivate = false,
                    replacements = mapOf("github" to "Different"),
                    updatedAt = 101L
                )
            }.exceptionOrNull()
            assertTrue(invalidReplacement is IllegalArgumentException)
            assertEquals(1, transactionCount)
            assertEquals("Updated note", bookmarkDao.rows.getValue("b1").note)
            assertEquals(listOf("git-hub", "Android"), bookmarkDao.occurrences.getValue("b1").map { it.value })
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test
    fun repositoryRespectsDomainPolicyStorePrivateFlagAndAtomicTogglePropagatesFailure() = runBlocking {
        val bookmarkDao = InMemoryBookmarkDao()
        val trashDao = InMemoryTrashDao()
        val policyDao = InMemoryDomainPolicyDao()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        var failNextTx = false
        val atomicRunner = object : TransactionRunner {
            override suspend fun <R> run(block: suspend () -> R): R {
                val policySnapshot = LinkedHashMap(policyDao.rows)
                val bookmarkSnapshot = LinkedHashMap(bookmarkDao.rows)
                return try {
                    val result = block()
                    if (failNextTx) throw IllegalStateException("policy write failed")
                    result
                } catch (e: Throwable) {
                    policyDao.rows.clear()
                    policyDao.rows.putAll(policySnapshot)
                    bookmarkDao.rows.clear()
                    bookmarkDao.rows.putAll(bookmarkSnapshot)
                    throw e
                }
            }
        }
        try {
            val policyStore = DomainPolicyStore(
                database = fakeAppDatabase(bookmarkDao = bookmarkDao, domainPolicyDao = policyDao),
                transactionRunner = atomicRunner,
                storeScope = scope
            )
            val repository = testRepository(
                bookmarkDao = bookmarkDao,
                trashDao = trashDao,
                scope = scope,
                transactionRunner = atomicRunner,
                domainPolicyStore = policyStore
            )
            policyStore.setDomainPrivateAndAwait(" EXAMPLE.COM ", true)
            repository.addBookmarkAndAwait(
                BookmarkItem(id = "b1", title = "One", url = "https://example.com/1", description = "", tag = defaultTag, isPrivate = false)
            )
            repository.addBookmarkAndAwait(
                BookmarkItem(id = "b2", title = "Two", url = "https://other.com/2", description = "", tag = defaultTag, isPrivate = false)
            )

            assertTrue(bookmarkDao.rows.getValue("b1").isPrivate)
            assertFalse(bookmarkDao.rows.getValue("b2").isPrivate)

            failNextTx = true
            val thrown = runCatching {
                policyStore.setDomainPrivateAndAwait("example.com", false)
            }.exceptionOrNull()
            assertTrue(thrown is IllegalStateException)
            assertEquals("policy write failed", thrown?.message)
            assertTrue(bookmarkDao.rows.getValue("b1").isPrivate)
            assertTrue(policyStore.isPrivateDomain("example.com"))
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test
    fun addBookmarkReadsCommittedPrivatePolicyFromDbEvenWhenCacheIsStale() = runBlocking {
        val bookmarkDao = InMemoryBookmarkDao()
        val trashDao = InMemoryTrashDao()
        val policyDao = InMemoryDomainPolicyDao()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val policyStore = DomainPolicyStore(
                database = fakeAppDatabase(bookmarkDao = bookmarkDao, domainPolicyDao = policyDao),
                transactionRunner = directTransactionRunner(),
                storeScope = scope
            )
            policyStore.awaitReady()
            // Simulate a policy committed to DB before the store's in-memory cache is published.
            policyDao.rows["stale-cache.example"] = me.bubblespark.tidilo.data.db.DomainPolicyEntity(
                domain = "stale-cache.example",
                isPrivate = true,
                displayName = ""
            )
            assertFalse(policyStore.isPrivateDomain("stale-cache.example"))

            val repository = testRepository(
                bookmarkDao = bookmarkDao,
                trashDao = trashDao,
                scope = scope,
                transactionRunner = directTransactionRunner(),
                domainPolicyStore = policyStore
            )
            repository.addBookmarkAndAwait(
                BookmarkItem(
                    id = "b-stale",
                    title = "Stale Cache",
                    url = "https://stale-cache.example/post",
                    description = "",
                    tag = defaultTag,
                    isPrivate = false
                )
            )

            assertTrue(bookmarkDao.rows.getValue("b-stale").isPrivate)
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }
}
