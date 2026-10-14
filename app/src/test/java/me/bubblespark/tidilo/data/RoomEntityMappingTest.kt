package me.bubblespark.tidilo.data

import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.data.db.Converters
import me.bubblespark.tidilo.data.db.TrashedBookmarkEntity
import me.bubblespark.tidilo.ui.screens.library.BookmarkItem
import me.bubblespark.tidilo.ui.screens.library.Tag
import me.bubblespark.tidilo.ui.screens.library.TrashedBookmarkItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomEntityMappingTest {

    private val converters = Converters()

    @Test
    fun testConvertersStringListRoundtrip() {
        val originalList = listOf("Android", "Kotlin", "Room", "Jetpack Compose")
        val json = converters.fromStringList(originalList)
        val deserialized = converters.toStringList(json)

        assertEquals(originalList, deserialized)
    }

    @Test
    fun testConvertersNullAndEmptyHandling() {
        assertEquals("[]", converters.fromStringList(null))
        assertEquals("[]", converters.fromStringList(emptyList()))
        assertEquals(emptyList<String>(), converters.toStringList(null))
        assertEquals(emptyList<String>(), converters.toStringList(""))
        assertEquals(emptyList<String>(), converters.toStringList("[]"))
    }

    @Test
    fun testBookmarkEntityBidirectionalMapping() {
        val now = 1776000000000L
        val original = BookmarkItem(
            id = "test-123",
            title = "Test Article Title",
            url = "https://example.com/article",
            description = "A comprehensive description of the test article.",
            tag = Tag.create(
                category = "Article",
                subcategory = "Tech",
                entities = listOf("Architecture", "Room", "SQLite")
            ),
            createdAt = now,
            updatedAt = now + 5000L,
            note = "Important note regarding architecture.",
            isNsfw = true
        )

        val entity = BookmarkEntity.fromBookmarkItem(original)
        assertEquals("test-123", entity.id)
        assertEquals("Test Article Title", entity.title)
        assertEquals("https://example.com/article", entity.url)
        assertEquals("A comprehensive description of the test article.", entity.description)
        assertEquals("Article", entity.category)
        assertEquals("Tech", entity.subcategory)
        assertEquals(listOf("Architecture", "Room", "SQLite"), entity.entities)
        assertEquals(now, entity.createdAt)
        assertEquals(now + 5000L, entity.updatedAt)
        assertEquals("Important note regarding architecture.", entity.note)
        assertTrue(entity.isNsfw)

        val restored = entity.toBookmarkItem()
        assertEquals(original.id, restored.id)
        assertEquals(original.title, restored.title)
        assertEquals(original.url, restored.url)
        assertEquals(original.description, restored.description)
        assertEquals(original.tag.category, restored.tag.category)
        assertEquals(original.tag.subcategory, restored.tag.subcategory)
        assertEquals(original.tag.entities, restored.tag.entities)
        assertEquals(original.createdAt, restored.createdAt)
        assertEquals(original.updatedAt, restored.updatedAt)
        assertEquals(original.note, restored.note)
        assertEquals(original.isNsfw, restored.isNsfw)
    }

    @Test
    fun testTrashedBookmarkEntityBidirectionalMapping() {
        val now = 1776000000000L
        val bookmark = BookmarkItem(
            id = "trash-1",
            title = "Trashed Item Title",
            url = "https://example.com/trash",
            description = "Trashed description",
            tag = Tag.create("Media", "Hobby", listOf("Video")),
            createdAt = now,
            updatedAt = now,
            note = "",
            isNsfw = false
        )
        val trashedItem = TrashedBookmarkItem(
            bookmark = bookmark,
            trashedAt = now + 10000L
        )

        val entity = TrashedBookmarkEntity.fromTrashedBookmarkItem(trashedItem)
        assertEquals("trash-1", entity.id)
        assertEquals(now + 10000L, entity.trashedAt)
        assertEquals("Trashed Item Title", entity.title)
        assertFalse(entity.isNsfw)

        val restored = entity.toTrashedBookmarkItem()
        assertEquals(trashedItem.bookmark.id, restored.bookmark.id)
        assertEquals(trashedItem.bookmark.title, restored.bookmark.title)
        assertEquals(trashedItem.bookmark.url, restored.bookmark.url)
        assertEquals(trashedItem.trashedAt, restored.trashedAt)
    }
}
