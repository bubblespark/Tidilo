package me.bubblespark.tidilo.ui.screens.discover

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import java.util.UUID
import me.bubblespark.tidilo.ui.screens.library.BookmarkItem
import me.bubblespark.tidilo.ui.screens.library.Tag
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test fun posterTextFitsThePlateAtDoubleFontScale() {
        val title = "A long poster title that would need several lines on a small two-up grid plate"
        composeRule.setContent {
            TidiloTheme(darkTheme = false, dynamicColor = false, blurEnabled = false) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                    androidx.compose.foundation.layout.Column {
                        layouts.filterNot { it.second }.forEach { (id, _) ->
                            // The two-up Library grid plate (182dp wide).
                            Box(Modifier.width(182.dp).aspectRatio(PLATE_RATIO)) {
                                DiscoverPoster(item(id).copy(title = title), Color(0xFF4285F4), null, compact = false)
                            }
                        }
                    }
                }
            }
        }
        val texts = composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertTrue(texts.isNotEmpty())
        texts.forEach { node ->
            val layouts = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            val layout = layouts.single()
            // Ellipsizing at maxLines is intended; drawing lines taller than the space given is the clip.
            val drawn = layout.multiParagraph.height
            val available = layout.layoutInput.constraints.maxHeight
            assertTrue("'${layout.layoutInput.text}' needs ${drawn}px but has ${available}px", drawn <= available)
        }
    }

    @Test fun missingOrBlurryIconKeepsInitial() {
        var icon by mutableStateOf<ImageBitmap?>(null)
        show { icon }
        // 4px stays blurry for the 16dp masthead tile down to 1.0x density (4 * 2 < 16).
        for (case in listOf(null, ImageBitmap(4, 4))) {
            composeRule.runOnIdle { icon = case }
            assertEquals(0, count(hasTestTag(POSTER_ICON_TAG)))
            assertEquals(3, count(hasText(initial)))
            assertEquals(1, count(hasText(domain.uppercase())))
        }
    }
}
