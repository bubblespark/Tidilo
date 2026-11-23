package me.bubblespark.tidilo.ui.screens.discover

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.data.discover.DiscoverReason
import me.bubblespark.tidilo.data.discover.DiscoverShelf
import me.bubblespark.tidilo.data.discover.OnThisDayPeriod
import me.bubblespark.tidilo.data.engagement.EngagementSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoverTextTest {
    @Test fun everyCountedReasonIsPlural() {
        assertEquals(TextSpec.Plural(R.plurals.discover_reason_dormant, 94), reasonTextSpec(DiscoverReason.DormantNeverOpened(94)))
        assertEquals(TextSpec.Plural(R.plurals.discover_reason_rss_expiring, 1), reasonTextSpec(DiscoverReason.RssExpiring(1)))
        assertEquals(TextSpec.Plural(R.plurals.discover_reason_rising, 4), reasonTextSpec(DiscoverReason.Rising(4)))
        assertEquals(TextSpec.Plain(R.string.discover_reason_affinity, listOf("Kotlin")), reasonTextSpec(DiscoverReason.AffinityEntity("Kotlin")))
        assertEquals(TextSpec.Plain(R.string.discover_reason_connection, listOf("Rust")), reasonTextSpec(DiscoverReason.Connection("Rust")))
        assertEquals(TextSpec.Plain(R.string.discover_reason_on_this_day_six_months), reasonTextSpec(DiscoverReason.OnThisDay(OnThisDayPeriod.SIX_MONTHS)))
    }

    @Test fun everyShelfHasATitleAndCardShelvesHaveTheirOwnSurface() {
        assertEquals(DiscoverShelf.entries.size, DiscoverShelf.entries.map(::shelfTitleRes).toSet().size)
        val cardShelves = DiscoverShelf.entries - DiscoverShelf.MAP
        assertEquals(cardShelves.size, cardShelves.map(::surfaceOf).toSet().size)
        assertTrue(cardShelves.none { surfaceOf(it) == EngagementSurface.UNKNOWN })
        assertEquals(EngagementSurface.DISCOVER_DECK, surfaceOf(DiscoverShelf.DECK))
    }

    @Test fun platesKeepTheRatioAndFitTheWindow() {
        PlateSize.entries.forEach { assertEquals(it.width.value / 1.91f, it.height.value, 0.01f) }
        assertEquals(360f, plateWidth(PlateSize.XL, 360.dp).value, 0f)
        assertEquals(280f, plateWidth(PlateSize.M, 412.dp).value, 0f)
        assertEquals(268f, plateWidth(PlateSize.L, 300.dp).value, 0f)
        assertTrue(fillsPlate(1200f / 630f))
        assertFalse(fillsPlate(1f))
        assertFalse(fillsPlate(4f / 3f))
    }

    @Test fun posterTextReachesAaContrast() {
        val samples = listOf(0xFFFFFFFF, 0xFFF5F1E9, 0xFFFFEB3B, 0xFF00BCD4, 0xFF8BC34A, 0xFFFF9800, 0xFF9C27B0,
            0xFF1D1F20, 0xFF4285F4, 0xFFE91E63, 0xFF607D8B, 0xFF808080).map { Color(it) }
        for (background in samples) {
            val ink = posterInk(background)
            val behind = Color.Black.copy(alpha = ink.textRegionDarkening).compositeOver(background)
            assertTrue("$background", contrastRatio(ink.text, behind) >= 4.5f)
        }
        assertEquals(0f, posterInk(Color(0xFF1D1F20)).textRegionDarkening, 0f)
    }

    @Test fun posterVariantIsStableAndInRange() {
        (1..50).map { "id$it" }.forEach { assertTrue(posterVariant(it) in 0..2); assertEquals(posterVariant(it), posterVariant(it)) }
    }
}
