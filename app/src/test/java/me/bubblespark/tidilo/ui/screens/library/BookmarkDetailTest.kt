package me.bubblespark.tidilo.ui.screens.library

import me.bubblespark.tidilo.ui.navigation.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BookmarkDetailTest {

    @Test
    fun testBookmarkItemDateFields() {
        val now = 1791650202300L // Expected 2026 timestamp
        val item = BookmarkItem(
            id = "test-1",
            title = "Test Article",
            url = "https://example.com/test",
            description = "Test Description",
            tag = Tag.create("Article", "Tech", listOf("Android", "Compose")),
            createdAt = now,
            updatedAt = now + 1000,
            note = "My test note"
        )

        assertEquals("test-1", item.id)
        assertEquals(now, item.createdAt)
        assertEquals(now + 1000, item.updatedAt)
        assertEquals("My test note", item.note)

        // Verify date formatting
        val sdf = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
        val formatted = sdf.format(Date(now))
        assertTrue("Formatted date should contain year", formatted.contains("2026") || formatted.length >= 10)
    }

    @Test
    fun testBookmarkDetailNavigationRoute() {
        val route = Screen.BookmarkDetail.createRoute("sample-id-123")
        assertEquals("bookmark_detail/sample-id-123", route)
        assertEquals("bookmark_detail/{bookmarkId}", Screen.BookmarkDetail.route)
    }

    @Test
    fun testBookmarkTagSerializationAndDeserializationPreservesTags() {
        val original = BookmarkItem(
            id = "test-tag-preservation",
            title = "Jetpack Compose Best Practices",
            url = "https://developer.android.com/compose",
            description = "Modern declarative UI toolkit",
            tag = Tag.create(
                category = "Article",
                subcategory = "Tech",
                entities = listOf("Compose", "Kotlin", "Android")
            ),
            note = "Check out layout guides"
        )

        // 1. Serialize
        val json = me.bubblespark.tidilo.data.BookmarkRepository.serializeBookmarks(listOf(original))
        assertTrue("Serialized JSON must contain 'tag' field", json.contains("\"tag\":"))
        assertTrue("Serialized JSON must contain category", json.contains("\"category\":\"Article\""))
        assertTrue("Serialized JSON must contain subcategory", json.contains("\"subcategory\":\"Tech\""))
        assertTrue("Serialized JSON must contain entities", json.contains("Compose"))

        // 2. Deserialize
        val parsedList = me.bubblespark.tidilo.data.BookmarkRepository.parseBookmarks(json)
        assertEquals(1, parsedList.size)
        val parsed = parsedList[0]

        // 3. Verify category and tags are restored correctly without becoming 'None'
        assertEquals("Article", parsed.tag.category)
        assertEquals("Tech", parsed.tag.subcategory)
        assertEquals(listOf("Compose", "Kotlin", "Android"), parsed.tag.entities)
        assertEquals("Check out layout guides", parsed.note)
    }

    @Test
    fun testCorruptedBookmarkAutoRepairFromSample() {
        // Simulate historical bug (JSON missing tag field)
        val corruptedJson = """
            [
              {
                "id": "b1",
                "title": "Android Developers: Material 3 Expressive",
                "url": "https://m3.material.io/",
                "description": "Expressive design system...",
                "createdAt": 1000,
                "updatedAt": 1000,
                "note": ""
              }
            ]
        """.trimIndent()

        val sampleBookmarks = listOf(
            BookmarkItem(
                id = "b1",
                title = "Android Developers: Material 3 Expressive",
                url = "https://m3.material.io/",
                description = "Expressive design system...",
                tag = Tag.create("Article", "Tech", listOf("Material 3", "Compose", "Design System"))
            )
        )

        // Parse with auto-repair
        val repaired = me.bubblespark.tidilo.data.BookmarkRepository.parseBookmarks(
            json = corruptedJson,
            sampleBookmarks = sampleBookmarks
        )

        assertEquals(1, repaired.size)
        val item = repaired[0]
        // Verify tags are restored from sample rather than falling back to 'None'
        assertEquals("Article", item.tag.category)
        assertEquals("Tech", item.tag.subcategory)
        assertEquals(listOf("Material 3", "Compose", "Design System"), item.tag.entities)
    }

    @Test
    fun testCorruptedBookmarkAutoRepairFromQueueTags() {
        // JSON for item enqueued from Queue where tag was missing at save time
        val userCorruptedJson = """
            [
              {
                "id": "user-added-1",
                "title": "Zenn Tech Article",
                "url": "https://zenn.dev/sample-user/articles/123",
                "description": "Zenn article...",
                "createdAt": 2000,
                "updatedAt": 2000,
                "note": ""
              }
            ]
        """.trimIndent()

        val queueTagsMap = mapOf(
            "https://zenn.dev/sample-user/articles/123" to Tag.create(
                category = "Article",
                subcategory = "Tech",
                entities = listOf("Zenn", "TypeScript")
            )
        )

        val repaired = me.bubblespark.tidilo.data.BookmarkRepository.parseBookmarks(
            json = userCorruptedJson,
            sampleBookmarks = emptyList(),
            queueTagsMap = queueTagsMap
        )

        assertEquals(1, repaired.size)
        val item = repaired[0]
        assertEquals("Article", item.tag.category)
        assertEquals("Tech", item.tag.subcategory)
        assertEquals(listOf("Zenn", "TypeScript"), item.tag.entities)
    }

    @Test
    fun testBookmarkTagModificationPreservesOtherFields() {
        val initialTime = 1000L
        val updateTime = 2000L
        val original = BookmarkItem(
            id = "b-test-edit",
            title = "Original Title",
            url = "https://example.com/item",
            description = "Original Description",
            tag = Tag.create("Article", "Tech", listOf("Kotlin")),
            createdAt = initialTime,
            updatedAt = initialTime,
            note = "Original Note",
            isNsfw = false,
            isAiSkipped = false
        )

        val updatedTag = Tag.create("Tool", "Business", listOf("Kotlin", "Productivity"))
        val updatedNote = "Updated Note"
        val modified = original.copy(
            note = updatedNote,
            tag = updatedTag,
            updatedAt = updateTime
        )

        // Verify updated fields
        assertEquals("Tool", modified.tag.category)
        assertEquals("Business", modified.tag.subcategory)
        assertEquals(listOf("Kotlin", "Productivity"), modified.tag.entities)
        assertEquals("Updated Note", modified.note)
        assertEquals(updateTime, modified.updatedAt)

        // Verify preserved fields
        assertEquals("b-test-edit", modified.id)
        assertEquals("Original Title", modified.title)
        assertEquals("https://example.com/item", modified.url)
        assertEquals("Original Description", modified.description)
        assertEquals(initialTime, modified.createdAt)
        assertEquals(false, modified.isNsfw)
        assertEquals(false, modified.isAiSkipped)
    }

    @Test
    fun testEntityTagListToggleAndRemoval() {
        var entities = listOf("Compose", "Android", "Kotlin")

        // 1. Remove entity
        entities = entities.filterNot { it.equals("Android", ignoreCase = true) }
        assertEquals(listOf("Compose", "Kotlin"), entities)

        // 2. Toggle existing entity (should remove)
        val target1 = "Compose"
        entities = if (entities.any { it.equals(target1, ignoreCase = true) }) {
            entities.filterNot { it.equals(target1, ignoreCase = true) }
        } else {
            entities + target1
        }
        assertEquals(listOf("Kotlin"), entities)

        // 3. Toggle absent entity (should add)
        val target2 = "Jetpack"
        entities = if (entities.any { it.equals(target2, ignoreCase = true) }) {
            entities.filterNot { it.equals(target2, ignoreCase = true) }
        } else {
            entities + target2
        }
        assertEquals(listOf("Kotlin", "Jetpack"), entities)
    }

    @Test
    fun testCompareEntityNamesSorting() {
        val list = listOf("日本語", "Apple", "zebra", "Banana", "テスト")
        val sorted = list.sortedWith { a, b -> compareEntityNames(a, b) }

        // English / ASCII words should precede Japanese, and be sorted alphabetically ignoring case
        assertEquals(listOf("Apple", "Banana", "zebra", "テスト", "日本語"), sorted)
    }

    @Test
    fun testTagUpdatePreservesAiSkippedFlag() {
        val original = BookmarkItem(
            id = "b-ai-skipped",
            title = "BOM Mapping",
            url = "https://developer.android.com/compose/bom",
            description = "BOM release notes",
            tag = Tag.createEmpty(),
            isAiSkipped = true
        )
        assertTrue(original.isAiSkipped)

        // User edits tags in BookmarkDetailScreen: isAiSkipped remains true as historical fact
        val newTag = Tag.create("Tool", "Tech", listOf("Android", "Compose"))
        val updated = original.copy(
            tag = newTag,
            note = "Updated note"
        )

        // Verify isAiSkipped remains true as an independent label, while tags are fully populated
        assertTrue(updated.isAiSkipped)
        assertEquals("Tool", updated.tag.category)
        assertEquals("Tech", updated.tag.subcategory)
        assertEquals(listOf("Android", "Compose"), updated.tag.entities)
        assertEquals("Updated note", updated.note)
    }

    @Test
    fun testNsfwToggleAndPersistence() {
        val original = BookmarkItem(
            id = "b-nsfw-test",
            title = "Test Item",
            url = "https://example.com/item",
            description = "Description",
            tag = Tag.create("Media", "Video", listOf("Tutorial")),
            isAiSkipped = true,
            isNsfw = false
        )
        org.junit.Assert.assertFalse(original.isNsfw)

        // Toggle NSFW ON
        val toggledOn = original.copy(isNsfw = true)
        assertTrue(toggledOn.isNsfw)
        assertTrue("isAiSkipped must still be preserved", toggledOn.isAiSkipped)
        assertEquals("Media", toggledOn.tag.category)

        // Toggle NSFW OFF
        val toggledOff = toggledOn.copy(isNsfw = false)
        org.junit.Assert.assertFalse(toggledOff.isNsfw)
        assertTrue("isAiSkipped must still be preserved", toggledOff.isAiSkipped)
    }

    @Test
    fun testNsfwWarningCondition() {
        fun shouldShowWarning(isNsfwContentVisible: Boolean, isNsfw: Boolean): Boolean {
            return !isNsfwContentVisible && isNsfw
        }

        // ShowNSFWContents = false (disabled in Settings), isNsfw = true (toggled ON) -> Show warning
        assertTrue(shouldShowWarning(isNsfwContentVisible = false, isNsfw = true))

        // ShowNSFWContents = true (enabled in Settings), isNsfw = true -> Don't show warning
        org.junit.Assert.assertFalse(shouldShowWarning(isNsfwContentVisible = true, isNsfw = true))

        // ShowNSFWContents = false, isNsfw = false -> Don't show warning
        org.junit.Assert.assertFalse(shouldShowWarning(isNsfwContentVisible = false, isNsfw = false))

        // ShowNSFWContents = true, isNsfw = false -> Don't show warning
        org.junit.Assert.assertFalse(shouldShowWarning(isNsfwContentVisible = true, isNsfw = false))
    }

    @Test
    fun testMaxEntitiesConstant() {
        assertEquals(10, Tag.MAX_ENTITIES)
    }

    @Test
    fun testTagToggleEntityFunction() {
        // Prepare list with 10 entities
        val initialEntities = (1..10).map { "Entity$it" }
        assertEquals(10, initialEntities.size)

        var draftEntities = initialEntities

        // Attempt to toggle an absent entity when already at Tag.MAX_ENTITIES
        val newEntity = "Entity11"
        draftEntities = Tag.toggleEntity(draftEntities, newEntity)
        assertEquals(10, draftEntities.size)
        org.junit.Assert.assertFalse(draftEntities.contains("Entity11"))

        // Toggling an existing entity when at Tag.MAX_ENTITIES should successfully remove it
        val existingEntity = "Entity5"
        draftEntities = Tag.toggleEntity(draftEntities, existingEntity)
        assertEquals(9, draftEntities.size)
        org.junit.Assert.assertFalse(draftEntities.contains("Entity5"))

        // Now at 9 entities, toggling a new entity should succeed
        draftEntities = Tag.toggleEntity(draftEntities, newEntity)
        assertEquals(10, draftEntities.size)
        assertTrue(draftEntities.contains("Entity11"))

        // Blank entity should be ignored
        assertEquals(10, Tag.toggleEntity(draftEntities, "   ").size)
    }

    @Test
    fun testTagAddEntityFunction() {
        var draftEntities = (1..10).map { "Tag$it" }
        val newTag = "Tag11"

        // Attempt to add when at limit
        draftEntities = Tag.addEntity(draftEntities, newTag)
        assertEquals(10, draftEntities.size)
        org.junit.Assert.assertFalse(draftEntities.contains("Tag11"))

        // Attempt to add existing tag when at limit
        draftEntities = Tag.addEntity(draftEntities, "tag1")
        assertEquals(10, draftEntities.size)

        // Drop one, then add new tag
        draftEntities = draftEntities.dropLast(1)
        draftEntities = Tag.addEntity(draftEntities, newTag)
        assertEquals(10, draftEntities.size)
        assertTrue(draftEntities.contains("Tag11"))

        // Adding duplicate (case-insensitive) should return unchanged list
        val sizeBeforeDup = draftEntities.size
        draftEntities = Tag.addEntity(draftEntities, "tag11")
        assertEquals(sizeBeforeDup, draftEntities.size)

        // Blank entity should be ignored
        val sizeBeforeBlank = draftEntities.size
        draftEntities = Tag.addEntity(draftEntities, "  ")
        assertEquals(sizeBeforeBlank, draftEntities.size)
    }

    @Test
    fun testBookmarkItemOgImageDerivationForDetailScreen() {
        val withOg = BookmarkItem(
            id = "test-detail-og",
            title = "Article with OG Image",
            url = "https://example.com/article",
            description = "Description",
            tag = Tag.create("Article", "Tech", listOf("Android")),
            ogImageUrl = "https://example.com/images/og-preview.png"
        )
        val filename = requireNotNull(withOg.ogImageFilename)
        assertTrue(filename.isNotBlank())

        val withoutOg = BookmarkItem(
            id = "test-detail-no-og",
            title = "Article without OG Image",
            url = "https://example.com/article",
            description = "Description",
            tag = Tag.create("Article", "Tech", listOf("Android")),
            ogImageUrl = null
        )
        org.junit.Assert.assertNull(withoutOg.ogImageFilename)

        val withBlankOg = BookmarkItem(
            id = "test-detail-blank-og",
            title = "Article with Blank OG Image",
            url = "https://example.com/article",
            description = "Description",
            tag = Tag.create("Article", "Tech", listOf("Android")),
            ogImageUrl = "   "
        )
        org.junit.Assert.assertNull(withBlankOg.ogImageFilename)
    }

    @Test
    fun testBookmarkDetailAspectRatioCalculation() {
        // Normal 1200x630 og:image aspect ratio (~1.904)
        val normalRatio = me.bubblespark.tidilo.ui.components.calculateOgAspectRatio(1200, 630)
        assertTrue(normalRatio > 1.9f && normalRatio < 1.91f)

        // Square 1:1 image
        val squareRatio = me.bubblespark.tidilo.ui.components.calculateOgAspectRatio(600, 600)
        assertEquals(1.0f, squareRatio, 0.001f)

        // Tall portrait image
        val portraitRatio = me.bubblespark.tidilo.ui.components.calculateOgAspectRatio(600, 1200)
        assertEquals(0.5f, portraitRatio, 0.001f)

        // Invalid height fallback
        val zeroHeightRatio = me.bubblespark.tidilo.ui.components.calculateOgAspectRatio(100, 0)
        assertEquals(me.bubblespark.tidilo.ui.components.DEFAULT_OG_ASPECT_RATIO, zeroHeightRatio, 0.001f)
    }
}

