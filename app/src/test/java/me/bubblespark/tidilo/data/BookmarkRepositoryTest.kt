package me.bubblespark.tidilo.data

import me.bubblespark.tidilo.config.AppLanguage
import me.bubblespark.tidilo.service.GeminiTaggingService
import me.bubblespark.tidilo.ui.screens.library.BookmarkItem
import me.bubblespark.tidilo.ui.screens.library.Tag
import me.bubblespark.tidilo.ui.screens.library.TrashedBookmarkItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookmarkRepositoryTest {

    private val defaultTag = Tag.create("Article", "Tech", listOf("Test"))

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
    fun testBookmarkItemIsNsfwSerializationAndDeserialization() {
        val nsfwBookmark = BookmarkItem(
            id = "nsfw-1",
            title = "Adult Creative Work",
            url = "https://example.com/r18/item",
            description = "18+ content description",
            tag = Tag.create("Media", "Adult", listOf("Sample")),
            isNsfw = true
        )

        val json = BookmarkRepository.serializeBookmarkToObj(nsfwBookmark)
        assertTrue(json.optBoolean("isNsfw"))

        val restored = BookmarkRepository.parseBookmarkFromObj(json)
        assertTrue(restored.isNsfw)
        assertEquals("Adult Creative Work", restored.title)

        // Backward compatibility: old JSON without isNsfw field defaults to false
        json.remove("isNsfw")
        val backwardCompat = BookmarkRepository.parseBookmarkFromObj(json)
        assertFalse(backwardCompat.isNsfw)
    }

    @Test
    fun testBookmarkListIsNsfwSerializationAndDeserialization() {
        val mixedList = listOf(
            BookmarkItem(
                id = "clean-1",
                title = "Clean Tech",
                url = "https://example.com/tech",
                description = "Tech article",
                tag = Tag.create("Tech", "Tech"),
                isNsfw = false
            ),
            BookmarkItem(
                id = "nsfw-1",
                title = "Adult Artwork",
                url = "https://pixiv.net/artworks/123",
                description = "R-18 artwork",
                tag = Tag.create("Media", "Adult"),
                isNsfw = true
            )
        )

        // Serialize full list to JSON string (same as saved in SharedPreferences)
        val serializedJson = BookmarkRepository.serializeBookmarks(mixedList)
        assertTrue(serializedJson.contains("\"isNsfw\":true"))

        // Parse list back (same as loadFromPrefs on screen reload/app restart)
        val parsedList = BookmarkRepository.parseBookmarks(serializedJson)
        assertEquals(2, parsedList.size)
        assertFalse("clean-1 should retain isNsfw = false", parsedList[0].isNsfw)
        assertTrue("nsfw-1 MUST retain isNsfw = true across list deserialization", parsedList[1].isNsfw)
    }

    @Test
    fun testAdultSubcategoryJapaneseLabelIs大人の嗜好() {
        assertEquals("大人の嗜好", Subcategory.ADULT.jaLabel)
        assertEquals("Adult", Subcategory.ADULT.enLabel)
        assertEquals(11, Subcategory.entries.size)
    }

    @Test
    fun testDomainPolicyManagerRegistrationAndToggling() {
        DomainPolicyManager.clearPoliciesForTest()

        // 1. Initial registration defaults to false
        DomainPolicyManager.registerDomain("pixiv.net")
        assertFalse(DomainPolicyManager.isNsfwDomain("pixiv.net"))

        // 2. Set NSFW flag to true
        DomainPolicyManager.setDomainNsfw("pixiv.net", true)
        assertTrue(DomainPolicyManager.isNsfwDomain("pixiv.net"))

        // 3. Sync domain does not overwrite existing true flag
        DomainPolicyManager.syncDomains(listOf("pixiv.net", "dlsite.com"))
        assertTrue(DomainPolicyManager.isNsfwDomain("pixiv.net"))
        assertFalse(DomainPolicyManager.isNsfwDomain("dlsite.com"))

        // 4. Listing policies
        val policies = DomainPolicyManager.getAllPolicies()
        assertEquals(2, policies.size)
        assertEquals("dlsite.com", policies[0].domain)
        assertFalse(policies[0].isNsfw)
        assertEquals("pixiv.net", policies[1].domain)
        assertTrue(policies[1].isNsfw)

        DomainPolicyManager.clearPoliciesForTest()
    }

    @Test
    fun testDomainPolicyManagerDisplayName() {
        DomainPolicyManager.clearPoliciesForTest()

        // 1. Register with displayName
        DomainPolicyManager.registerDomain("github.com", false, "GitHub")
        assertEquals("GitHub", DomainPolicyManager.getDomainDisplayName("github.com"))
        assertEquals("GitHub", DomainPolicyManager.getDisplayName("github.com"))

        // 2. Register without displayName falls back to domain
        DomainPolicyManager.registerDomain("example.com", false)
        assertEquals("", DomainPolicyManager.getDomainDisplayName("example.com"))
        assertEquals("example.com", DomainPolicyManager.getDisplayName("example.com"))

        // 3. Update displayName
        DomainPolicyManager.setDomainDisplayName("example.com", "Example Site")
        assertEquals("Example Site", DomainPolicyManager.getDisplayName("example.com"))

        DomainPolicyManager.clearPoliciesForTest()
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
}
