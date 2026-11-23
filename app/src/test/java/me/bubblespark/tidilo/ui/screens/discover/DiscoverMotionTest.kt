package me.bubblespark.tidilo.ui.screens.discover

import me.bubblespark.tidilo.data.discover.DiscoverPick
import me.bubblespark.tidilo.ui.screens.library.BookmarkItem
import me.bubblespark.tidilo.ui.screens.library.Tag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiscoverMotionTest {
    @Test fun centerItemIsTheOneNearestTheViewportCenter() {
        assertNull(centerItemKey(0, 1000, emptyList()))
        assertEquals("only", centerItemKey(0, 1000, listOf(VisibleItem("only", -400, 500))))
        val items = listOf(VisibleItem("hero", -300, 700), VisibleItem("deck", 440, 400), VisibleItem("gems", 880, 600))
        assertEquals("deck", centerItemKey(0, 1000, items))
        // Content padding moves the viewport; the center follows it.
        assertEquals("gems", centerItemKey(600, 1600, items))
    }

    private fun item(id: String) = BookmarkItem(id, id, "https://fixture.invalid/$id", "", Tag.createEmpty(), createdAt = 1)

    @Test fun eachShelfIsLitByItsCurrentFirstCard() {
        val before = DiscoverUiState(hero = DiscoverPick(item("old-hero"), null),
            sleepingGems = listOf(DiscoverPick(item("gem-a"), null), DiscoverPick(item("gem-b"), null)),
            deck = DeckState(listOf(item("d0"), item("d1"))))
        assertEquals("old-hero", before.leadIds()[HERO_KEY])
        assertEquals("gem-a", before.leadIds()["discover_shelf_SLEEPING_GEMS"])
        assertEquals("d0", before.leadIds()["discover_shelf_DECK"])
        // A re-deal, a hidden first card, and a flick each move the lead.
        val after = before.copy(hero = DiscoverPick(item("new-hero"), null), sleepingGems = before.sleepingGems.drop(1),
            deck = before.deck.next())
        assertEquals("new-hero", after.leadIds()[HERO_KEY])
        assertEquals("gem-b", after.leadIds()["discover_shelf_SLEEPING_GEMS"])
        assertEquals("d1", after.leadIds()["discover_shelf_DECK"])
    }
}
