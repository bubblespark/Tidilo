package me.bubblespark.tidilo.data

import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.ui.screens.library.BookmarkItem
import me.bubblespark.tidilo.ui.screens.library.Tag
import me.bubblespark.tidilo.ui.theme.Typography
import me.bubblespark.tidilo.ui.util.TimeFormatter
import org.junit.Assert.*
import org.junit.Test

/** Real card bounds catch cropped artwork and insufficient date clearance. */
class BookmarkGridGeometryTest {
    @Test fun artworkKeepsSixteenByNineFraming(): Unit = verifyCards(true)
    @Test fun datesClearCornersAndCardsAlignWithDifferentTitles(): Unit = verifyCards(false)
    @Test fun cappedArtworkAndFallbackKeepTheLikeTarget(): Unit = verifyCards(true, 26.dp)

    private fun verifyCards(withImage: Boolean, mediaHeight: Dp? = null): Unit = runBlocking {
        val host = HeaderTestHost()
        val root = File(host.context.cacheDir, "grid-geometry-${UUID.randomUUID()}")
        val context = object : ContextWrapper(host.context) {
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
        val scale = mutableStateOf(1f)
        val cards = Array(2) { Rect.Zero }
        val likeToggles = AtomicInteger()
        val oldDownload = OgImageManager.imageDownloader
        val oldIconDownload = DomainIconManager.httpDownloader
        val oldDiscovery = DomainIconManager.htmlDiscoverer
        val image = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
        image.eraseColor(android.graphics.Color.GREEN)
        val bytes = ByteArrayOutputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
        image.recycle()
        OgImageManager.imageDownloader = { bytes }
        DomainIconManager.httpDownloader = { DomainIconManager.DownloadResult(null, true, false) }
        DomainIconManager.htmlDiscoverer = { DomainIconManager.HtmlDiscoveryResult(emptyList(), true, false) }
        val activity = host.launch()
        // Fit two production-sized cells at 200% even on a phone-width test display.
        val density = minOf(
            context.resources.displayMetrics.density,
            context.resources.displayMetrics.widthPixels / 576f
        )
        try {
            host.main {
                activity.setContent {
                    CompositionLocalProvider(LocalContext provides context,
                        LocalDensity provides Density(density, scale.value)) {
                        MaterialTheme(typography = Typography) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(short, long).forEachIndexed { index, bookmark ->
                                    BookmarkGridCardFixture(
                                        bookmark = bookmark,
                                        modifier = Modifier.onGloballyPositioned { cards[index] = it.boundsInRoot() },
                                        widthModifier = Modifier.width(if (scale.value >= 2f) 280.dp else 173.dp),
                                        mediaHeight = mediaHeight,
                                        onToggleLike = { likeToggles.incrementAndGet() }
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (withImage) host.await(activity) { host.description(it, short.title) }
            for (fontScale in listOf(1f, 1.15f, 2f)) {
                host.main { scale.value = fontScale }
                host.await(activity) { node ->
                    host.text(node, long.title) && textLayout(node)?.layoutInput?.density?.fontScale == fontScale
                }
                host.main {
                    val nodes = host.nodes(activity)
                    val domains = nodes.filter { host.text(it, domain) }.sortedBy { it.boundsInRoot.left }
                    val dates = listOf(short, long).map { bookmark ->
                        val date = TimeFormatter.formatRelativeTime(bookmark.createdAt, context)
                        nodes.single { host.text(it, date) }
                    }
                    val titles = listOf(short, long).map { bookmark -> nodes.single { host.text(it, bookmark.title) } }
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
                        assertEquals(expectedMedia, domainBounds.top - cards[index].top - 8f * density, 2f)
                        if (mediaHeight != null) {
                            val card = generateSequence(titles[index].parent) { it.parent }
                                .first { it.config.getOrNull(SemanticsActions.OnClick) != null }
                            val like = nodes.single { node ->
                                node.config.getOrNull(SemanticsProperties.Role) == Role.Button &&
                                    generateSequence(node.parent) { it.parent }.any { it.id == card.id }
                            }
                            val likeBounds = like.boundsInRoot
                            assertEquals(48f * density, likeBounds.width, 1f)
                            assertEquals(48f * density, likeBounds.height, 1f)
                            assertFalse(likeBounds.overlaps(domainBounds))
                            assertFalse(likeBounds.overlaps(titleBounds))
                            val count = likeToggles.get()
                            assertTrue(like.config[SemanticsActions.OnClick].action?.invoke() == true)
                            assertEquals(count + 1, likeToggles.get())
                        }
                    }
                    assertTrue(checkNotNull(textLayout(titles[1])).isLineEllipsized(2))
                    if (withImage) {
                        val artwork = nodes.single { host.description(it, short.title) }.boundsInRoot
                        val expected = mediaHeight?.value?.times(density) ?: (artwork.width * 9f / 16f)
                        assertEquals(expected, artwork.height, 1.1f)
                    }
                }
            }
        } finally {
            host.main { activity.finish() }
            OgImageManager.imageDownloader = oldDownload
            DomainIconManager.httpDownloader = oldIconDownload
            DomainIconManager.htmlDiscoverer = oldDiscovery
            root.deleteRecursively()
        }
    }

    private fun textLayout(node: SemanticsNode): TextLayoutResult? {
        val layouts = mutableListOf<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
        return layouts.singleOrNull()
    }
}
