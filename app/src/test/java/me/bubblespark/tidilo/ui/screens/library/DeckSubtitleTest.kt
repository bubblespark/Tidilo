package me.bubblespark.tidilo.ui.screens.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeckSubtitleTest {
    @Test fun blankSubtitleIsHidden() {
        assertNull(deckSubtitle("Example feed", ""))
        assertNull(deckSubtitle("Example feed", "   "))
    }

    @Test fun subtitleEqualToTitleIsHidden() {
        assertNull(deckSubtitle("github.com", "github.com"))
        assertNull(deckSubtitle("GitHub.com", " github.com "))
    }

    @Test fun distinctSubtitleIsTrimmedAndShown() {
        assertEquals("github.com", deckSubtitle("GitHub", " github.com"))
    }
}
