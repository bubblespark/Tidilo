package me.bubblespark.tidilo.ui.screens.library

import me.bubblespark.tidilo.data.BookmarkSource
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryBookmarkSourceTest {
    @Test fun newestFirstSwitchesBetweenManualAndAll() {
        assertEquals(BookmarkSource.MANUAL, libraryBookmarkSource(LibraryFilterMode.NEWEST_FIRST, includeRss = false))
        assertEquals(BookmarkSource.ALL, libraryBookmarkSource(LibraryFilterMode.NEWEST_FIRST, includeRss = true))
    }

    @Test fun otherModesIgnoreIncludeRss() {
        assertEquals(BookmarkSource.RSS, libraryBookmarkSource(LibraryFilterMode.RSS, includeRss = false))
        assertEquals(BookmarkSource.RSS, libraryBookmarkSource(LibraryFilterMode.RSS, includeRss = true))
        assertEquals(BookmarkSource.MANUAL, libraryBookmarkSource(LibraryFilterMode.DOMAIN, includeRss = true))
        assertEquals(BookmarkSource.MANUAL, libraryBookmarkSource(LibraryFilterMode.COLLECTION, includeRss = true))
    }
}
