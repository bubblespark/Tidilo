package me.bubblespark.tidilo.ui.screens.library

import org.junit.Assert.assertEquals
import org.junit.Test

class BookmarkGridLayoutTest {
    @Test fun shallowViewportAddsColumnsBeforeReducingArtworkHeight() {
        val layout = calculateBookmarkGridLayout(
            contentWidth = 900,
            minimumCardWidth = 160,
            horizontalGap = 8,
            minimumMediaHeight = 48,
            infoHeight = 100,
            availableHeight = { 245 }
        )

        assertEquals(4, layout.columns)
        assertEquals(123, layout.mediaHeightPx)
    }

    @Test fun exactWidthAllowsTwoReadableColumns() {
        fun columns(width: Int) =
            calculateBookmarkGridLayout(width, 160, 8, 48, 100) { 500 }.columns

        assertEquals(1, columns(327))
        assertEquals(2, columns(328))
    }

    @Test fun shallowerViewportCapsMediaAndPreservesThePreviewFloor() {
        fun media(height: Int) =
            calculateBookmarkGridLayout(900, 160, 8, 24, 100) { height }

        assertEquals(BookmarkGridSize(5, 80), media(180))
        assertEquals(BookmarkGridSize(5, 40), media(140))
        assertEquals(BookmarkGridSize(5, 24), media(115))
    }

    @Test fun widerFirstCardMustClearTheFloatingShortcut() {
        val layout = calculateBookmarkGridLayout(400, 160, 8, 48, 100) { cellWidth ->
            if (cellWidth > 150) 155 else 250
        }

        assertEquals(BookmarkGridSize(2, 55), layout)
    }
}
