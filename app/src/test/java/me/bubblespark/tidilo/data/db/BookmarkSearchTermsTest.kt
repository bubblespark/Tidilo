package me.bubblespark.tidilo.data.db

import org.junit.Assert.assertEquals
import org.junit.Test

class BookmarkSearchTermsTest {
    @Test fun reportsEveryMatchingFieldOnce() {
        val bookmark = BookmarkEntity(
            id = "bookmark", title = "Android notes", url = "https://example.test/android",
            description = "More about Android", category = "Article",
            entities = listOf("Kotlin Multiplatform"), createdAt = 0, updatedAt = 0
        )

        assertEquals(BookmarkMatchField.entries.toSet(),
            BookmarkSearchTerms.parse("Android　#記事 #Kotlin Android").matchingFields(bookmark))
        assertEquals(setOf(BookmarkMatchField.ENTITY_TAGS),
            BookmarkSearchTerms.parse("#kotlin").matchingFields(bookmark))
        assertEquals(emptySet<BookmarkMatchField>(), BookmarkSearchTerms.parse("#").matchingFields(bookmark))
    }
}
