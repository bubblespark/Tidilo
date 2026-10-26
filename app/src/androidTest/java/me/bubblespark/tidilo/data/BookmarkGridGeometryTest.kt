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
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.ui.screens.library.BookmarkGridCard
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

    private fun verifyCards(withImage: Boolean): Unit = runBlocking {
        val host = HeaderTestHost()
        val root = File(host.context.cacheDir, "grid-geometry-${UUID.randomUUID()}")
        val context = object : ContextWrapper(host.context) {
            override fun getCacheDir() = root.apply { mkdirs() }
        }
        val domain = "${UUID.randomUUID()}.example"
        val createdAt = System.currentTimeMillis() - 3600000
        val short = BookmarkItem("short", "Short", "https://$domain/a", "", Tag.createEmpty(),
            createdAt = createdAt, ogImageUrl = if (withImage) "https://$domain/image.png" else null)
        val long = short.copy(id = "long", title = "A long title that occupies all three reserved lines in this narrow card", url = "https://$domain/b")
        val scale = mutableStateOf(1f)
        val cards = Array(2) { Rect.Zero }
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
        val density = context.resources.displayMetrics.density
        try {
            host.main {
                activity.setContent {
                    CompositionLocalProvider(LocalContext provides context,
                        LocalDensity provides Density(density, scale.value)) {
                        MaterialTheme(typography = Typography) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(short, long).forEachIndexed { index, bookmark ->
                                    BookmarkGridCard(bookmark, Modifier.width(173.dp).onGloballyPositioned { cards[index] = it.boundsInRoot() })
                                }
                            }
                        }
                    }
                }
            }
            if (withImage) {
                val node = host.await(activity) { host.description(it, short.title) }
                host.main {
                    val bounds = node.boundsInRoot
                    assertEquals("A 16:9 preview must fit without vertical cropping", 16f / 9f, bounds.width / bounds.height, 0.01f)
                    assertEquals(132f * density, cards[0].height - bounds.height, 1.1f)
                }
            } else {
                val date = TimeFormatter.formatRelativeTime(createdAt, context)
                host.await(activity) { host.text(it, date) }
                for (fontScale in listOf(1f, 1.15f)) {
                    host.main { scale.value = fontScale }
                    // Both captions must have been measured at the requested scale before checking bounds.
                    host.await(activity) { node ->
                        if (!host.text(node, date)) false else {
                            val dates = host.nodes(activity).filter { host.text(it, date) }
                            dates.size == 2 && dates.all { caption ->
                                val layouts = mutableListOf<TextLayoutResult>()
                                caption.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts) == true &&
                                    layouts.singleOrNull()?.layoutInput?.density?.fontScale == fontScale
                            }
                        }
                    }
                    host.main {
                        val dates = host.nodes(activity).filter { host.text(it, date) }.sortedBy { it.boundsInRoot.left }
                        assertEquals(2, dates.size)
                        assertEquals(cards[0].height, cards[1].height, 0f)
                        assertEquals(dates[0].boundsInRoot.bottom, dates[1].boundsInRoot.bottom, 0f)
                        dates.forEachIndexed { index, node ->
                            val layouts = mutableListOf<TextLayoutResult>()
                            assertTrue(node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts))
                            assertEquals(fontScale, layouts.single().layoutInput.density.fontScale, 0f)
                            assertEquals(1, layouts.single().lineCount)
                            assertFalse("The date must fit vertically at font scale $fontScale", layouts.single().didOverflowHeight)
                            assertTrue("Date must clear the rounded bottom at font scale $fontScale",
                                cards[index].bottom - node.boundsInRoot.bottom >= 12f * density - 1f)
                        }
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
}
