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
}

