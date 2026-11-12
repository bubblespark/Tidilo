package me.bubblespark.tidilo.ui.screens.library

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests verifying search result feedback layout dimensions and prevention of
 * phase-backwards writes / layout jumps on the search screen.
 */
class SearchResultFeedbackTest {

    @Test
    fun canonicalSearchInputHeightMatchesMaterial3Default() {
        assertEquals("Canonical search input height must be 56.dp", 56.dp, SEARCH_INPUT_HEIGHT)
    }

    @Test
    fun remainingHeightUsesCanonicalInputHeightByDefault() {
        val viewport = 600.dp
        val contentPadding = PaddingValues(top = 104.dp, bottom = 80.dp)
        val spacing = 16.dp

        val withDefault = calculateRemainingFeedbackHeight(
            viewportHeight = viewport,
            contentPadding = contentPadding,
            itemSpacing = spacing
        )
        val withExplicitCanonical = calculateRemainingFeedbackHeight(
            viewportHeight = viewport,
            contentPadding = contentPadding,
            searchInputHeight = SEARCH_INPUT_HEIGHT,
            itemSpacing = spacing
        )

        assertEquals(
            "Default search input height must match explicit SEARCH_INPUT_HEIGHT",
            withExplicitCanonical,
            withDefault
        )
        // 600 - 104 - 80 - 56 - 16 = 344.dp
        assertEquals(344.dp, withDefault)
    }

    @Test
    fun eliminatesFirstFrameHeightDiscontinuityFromZeroInitialState() {
        val viewport = 600.dp
        val contentPadding = PaddingValues(top = 104.dp, bottom = 80.dp)
        val spacing = 16.dp

        // In previous implementation, initial composition evaluated with searchInputHeight = 0.dp:
        val legacyFirstFrameHeight = calculateRemainingFeedbackHeight(
            viewportHeight = viewport,
            contentPadding = contentPadding,
            searchInputHeight = 0.dp,
            itemSpacing = spacing
        )
        // Followed by recomposition once measured height (56.dp) was written back:
        val settledFrameHeight = calculateRemainingFeedbackHeight(
            viewportHeight = viewport,
            contentPadding = contentPadding,
            searchInputHeight = 56.dp,
            itemSpacing = spacing
        )

        // The legacy jump between first frame and settled frame was exactly SEARCH_INPUT_HEIGHT:
        val legacyFrameJump = legacyFirstFrameHeight - settledFrameHeight
        assertEquals("Legacy backwards write caused a 56.dp layout jump on first frame", 56.dp, legacyFrameJump)

        // With canonical constant, first frame immediately resolves to settled height:
        val currentFirstFrameHeight = calculateRemainingFeedbackHeight(
            viewportHeight = viewport,
            contentPadding = contentPadding,
            itemSpacing = spacing
        )
        assertEquals("Current first frame must immediately match settled frame height", settledFrameHeight, currentFirstFrameHeight)
    }

    @Test
    fun remainingHeightClampsToZeroWhenViewportIsSmallerThanClearances() {
        val tinyViewport = 100.dp
        val contentPadding = PaddingValues(top = 104.dp, bottom = 80.dp)
        val spacing = 16.dp

        val remaining = calculateRemainingFeedbackHeight(
            viewportHeight = tinyViewport,
            contentPadding = contentPadding,
            itemSpacing = spacing
        )
        assertEquals("Remaining height must not be negative", 0.dp, remaining)
    }

    @Test
    fun customInputHeightSupportedForDynamicScenarios() {
        val viewport = 420.dp
        val contentPadding = PaddingValues(top = 104.dp, bottom = 80.dp)
        val spacing = 10.dp
        val customHeight = 84.dp

        val remaining = calculateRemainingFeedbackHeight(
            viewportHeight = viewport,
            contentPadding = contentPadding,
            searchInputHeight = customHeight,
            itemSpacing = spacing
        )
        // 420 - 104 - 80 - 84 - 10 = 142.dp
        assertEquals(142.dp, remaining)
    }
}
