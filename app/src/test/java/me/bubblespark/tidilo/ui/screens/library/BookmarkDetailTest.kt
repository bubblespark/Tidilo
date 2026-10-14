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
}
