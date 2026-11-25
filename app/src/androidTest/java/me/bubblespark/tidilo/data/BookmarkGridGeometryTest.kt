package me.bubblespark.tidilo.data

import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import me.bubblespark.tidilo.ui.screens.library.BookmarkItem
import me.bubblespark.tidilo.ui.screens.library.Tag
import me.bubblespark.tidilo.ui.screens.library.rememberBookmarkGridTextMetrics
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import me.bubblespark.tidilo.ui.util.TimeFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Real card bounds catch cropped artwork and insufficient date clearance. */
class BookmarkGridGeometryTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun artworkKeepsSixteenByNineFraming() = verifyCards(true)
    @Test fun datesClearCornersAndCardsAlignWithDifferentTitles() = verifyCards(false)
    @Test fun cappedArtworkAndFallbackKeepTheLikeTarget() = verifyCards(true, 26.dp)

    private fun verifyCards(withImage: Boolean, mediaHeight: Dp? = null) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "grid-geometry-${UUID.randomUUID()}")
        val context = object : ContextWrapper(base) {
            override fun getCacheDir() = root.apply { mkdirs() }
        }
        val domain = "${UUID.randomUUID()}.example"
        val createdAt = System.currentTimeMillis() - 58 * 60_000L
        val short = BookmarkItem("short", if (withImage) "Short" else "短いタイトル", "https://$domain/a", "", Tag.createEmpty(),
            createdAt = createdAt, ogImageUrl = if (withImage) "https://$domain/image.png" else null)
        val long = short.copy(
            id = "long",
            title = if (withImage) "読みやすいタイトルと日付が残ることを確認するためのとても長い日本語のブックマークタイトル".repeat(4)
                else "A long bookmark title needs to show the first three lines at the selected system font size. ".repeat(4),
            url = "https://$domain/b",
            createdAt = System.currentTimeMillis(),
            ogImageUrl = null
        )
        val scale = mutableFloatStateOf(1f)
        val cards = Array(2) { Rect.Zero }
        val likeToggles = AtomicInteger()
        var labelHeightPx = 0f
        // Fit two production-sized cells at 200% even on a phone-width test display.
        val density = minOf(
            context.resources.displayMetrics.density,
            context.resources.displayMetrics.widthPixels / 576f
        )
        val caches = OfflineVisualCaches { tinyPng(16, 9) }
        try {
            composeRule.setContent {
                CompositionLocalProvider(LocalContext provides context,
                    LocalDensity provides Density(density, scale.floatValue)) {
                    TidiloTheme(darkTheme = false, dynamicColor = false, blurEnabled = false) {
                        val labelHeight = with(LocalDensity.current) { rememberBookmarkGridTextMetrics().labelHeight.toPx() }
                        SideEffect { labelHeightPx = labelHeight }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(short, long).forEachIndexed { index, bookmark ->
                                BookmarkGridCardFixture(
                                    bookmark = bookmark,
                                    modifier = Modifier.onGloballyPositioned { cards[index] = it.boundsInRoot() },
                                    widthModifier = Modifier.width(if (scale.floatValue >= 2f) 280.dp else 173.dp),
                                    mediaHeight = mediaHeight,
                                    onToggleLike = { likeToggles.incrementAndGet() }
                                )
                            }
                        }
                    }
                }
            }
            if (withImage) composeRule.waitUntil(8_000) { nodes(hasContentDescription(short.title)).isNotEmpty() }
            for (fontScale in listOf(1f, 1.15f, 2f)) {
                composeRule.runOnIdle { scale.floatValue = fontScale }
                composeRule.waitUntil(8_000) {
                    nodes(hasText(long.title)).any { textLayout(it)?.layoutInput?.density?.fontScale == fontScale }
                }
                val domains = nodes(hasText(domain)).sortedBy { it.boundsInRoot.left }
                val dates = listOf(short, long).map { bookmark ->
                    nodes(hasText(TimeFormatter.formatRelativeTime(bookmark.createdAt, context))).single()
                }
                val titles = listOf(short, long).map { bookmark -> nodes(hasText(bookmark.title)).single() }
                assertEquals(2, domains.size)
                assertEquals(2, dates.size)
                assertEquals("Card height at $fontScale", cards[0].height, cards[1].height, 1f)
                assertEquals("Date alignment at $fontScale",
                    dates[0].boundsInRoot.bottom, dates[1].boundsInRoot.bottom, 1f)
                for (index in cards.indices) {
                    val domainBounds = domains[index].boundsInRoot
                    val titleBounds = titles[index].boundsInRoot
                    val dateBounds = dates[index].boundsInRoot
                    val titleLayout = checkNotNull(textLayout(titles[index]))
                    val dateLayout = checkNotNull(textLayout(dates[index]))
                    assertEquals(fontScale, dateLayout.layoutInput.density.fontScale, 0f)
                    assertEquals(1, checkNotNull(textLayout(domains[index])).lineCount)
                    assertTrue(titleLayout.lineCount in 1..3)
                    assertTrue(titleLayout.getLineBottom(titleLayout.lineCount - 1) <= titleBounds.height + 1f)
                    assertFalse("Relative time must fit vertically at $fontScale", dateLayout.didOverflowHeight)
                    repeat(dateLayout.lineCount) { line ->
                        assertTrue("Relative time must fit horizontally at $fontScale",
                            dateLayout.getLineLeft(line) >= -1f &&
                                dateLayout.getLineRight(line) <= dateBounds.width + 1f)
                    }
                    assertTrue(domainBounds.bottom <= titleBounds.top)
                    assertTrue(titleBounds.bottom <= dateBounds.top)
                    assertTrue(cards[index].bottom - dateBounds.bottom >= 12f * density - 1f)
                    val expectedMedia = mediaHeight?.value?.times(density) ?: (cards[index].width * 9f / 16f)
                    // The domain text is centered in a row at least one label slot tall; measure from the row top.
                    val domainRowTop = domainBounds.top - (maxOf(labelHeightPx, domainBounds.height) - domainBounds.height) / 2f
                    assertEquals("Media height at $fontScale (domain $domainBounds, label slot $labelHeightPx, card ${cards[index]})",
                        expectedMedia, domainRowTop - cards[index].top - 8f * density, 2f)
                    if (mediaHeight != null) assertLikeTarget(titles[index], domainBounds, titleBounds, density, likeToggles)
                }
                assertTrue(checkNotNull(textLayout(titles[1])).isLineEllipsized(2))
                if (withImage) {
                    val artwork = nodes(hasContentDescription(short.title)).single().boundsInRoot
                    val expected = mediaHeight?.value?.times(density) ?: (artwork.width * 9f / 16f)
                    assertEquals(expected, artwork.height, 1.1f)
                }
            }
        } finally {
            caches.close()
            root.deleteRecursively()
        }
    }

    private fun assertLikeTarget(
        title: SemanticsNode,
        domainBounds: Rect,
        titleBounds: Rect,
        density: Float,
        likeToggles: AtomicInteger
    ) {
        val card = generateSequence(title.parent) { it.parent }
            .first { it.config.getOrNull(SemanticsActions.OnClick) != null }
        val like = nodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).single { node ->
            generateSequence(node.parent) { it.parent }.any { it.id == card.id }
        }
        val likeBounds = like.boundsInRoot
        assertEquals(48f * density, likeBounds.width, 1f)
        assertEquals(48f * density, likeBounds.height, 1f)
        assertFalse(likeBounds.overlaps(domainBounds))
        assertFalse(likeBounds.overlaps(titleBounds))
        val count = likeToggles.get()
        composeRule.runOnUiThread { assertTrue(like.config[SemanticsActions.OnClick].action?.invoke() == true) }
        composeRule.waitForIdle()
        assertEquals(count + 1, likeToggles.get())
    }

    private fun nodes(matcher: SemanticsMatcher): List<SemanticsNode> =
        composeRule.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes()

    private fun textLayout(node: SemanticsNode): TextLayoutResult? {
        val layouts = mutableListOf<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
        return layouts.singleOrNull()
    }
}
