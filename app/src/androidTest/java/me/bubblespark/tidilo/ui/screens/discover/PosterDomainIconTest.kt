package me.bubblespark.tidilo.ui.screens.discover

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import java.util.UUID
import me.bubblespark.tidilo.ui.screens.library.BookmarkItem
import me.bubblespark.tidilo.ui.screens.library.Tag
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Posters (§8.2) show a sharp domain icon on a tile and keep the initial without one. */
class PosterDomainIconTest {
    @get:Rule val composeRule = createComposeRule()
    private val domain = "q${UUID.randomUUID().toString().take(8)}.example"
    private val initial = "Q"

    /** Variant 0, 1, 2 posters, then a compact one. */
    private val layouts: List<Pair<String, Boolean>> =
        (0..2).map { v -> generateSequence(0) { it + 1 }.map { "poster-$it" }.first { posterVariant(it) == v } to false } +
            ("poster-compact" to true)

    private fun item(id: String) = BookmarkItem(id, "Title of $id", "https://$domain/$id", "Summary", Tag.createEmpty(), domain = domain)

    private fun show(icon: () -> ImageBitmap?) {
        composeRule.setContent {
            TidiloTheme(darkTheme = false, dynamicColor = false, blurEnabled = false) {
                androidx.compose.foundation.layout.Column {
                    layouts.forEach { (id, compact) ->
                        Box(Modifier.width(if (compact) 112.dp else 182.dp).aspectRatio(PLATE_RATIO)) {
                            DiscoverPoster(item(id), Color(0xFF4285F4), icon(), compact)
                        }
                    }
                }
            }
        }
    }

    private fun count(matcher: androidx.compose.ui.test.SemanticsMatcher) =
        composeRule.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().size

    @Test fun sharpIconReplacesInitialInEveryLayout() {
        show { ImageBitmap(256, 256) }
        assertEquals(4, count(hasTestTag(POSTER_ICON_TAG)))
        assertEquals(0, count(hasText(initial)))
    }

    @Test fun missingOrBlurryIconKeepsInitial() {
        var icon by mutableStateOf<ImageBitmap?>(null)
        show { icon }
        for (case in listOf(null, ImageBitmap(8, 8))) {
            composeRule.runOnIdle { icon = case }
            assertEquals(0, count(hasTestTag(POSTER_ICON_TAG)))
            assertEquals(3, count(hasText(initial)))
            assertEquals(1, count(hasText(domain.uppercase())))
        }
    }
}
