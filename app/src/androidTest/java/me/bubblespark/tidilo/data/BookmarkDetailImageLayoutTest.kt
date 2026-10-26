package me.bubblespark.tidilo.data

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.key
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.MainActivity
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.ui.screens.library.BookmarkDetailScreen
import me.bubblespark.tidilo.ui.screens.library.LibraryViewModel
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/** Checks painted pixels: measured image bounds alone cannot detect layout overflow. */
class BookmarkDetailImageLayoutTest {
    @Test fun squareArtworkStaysInsideBanner() = verifyArtwork(400, 400)
    @Test fun portraitArtworkStaysInsideBanner() = verifyArtwork(200, 400)
    @Test fun landscapeArtworkKeepsNaturalHeight() = verifyArtwork(800, 200)
    @Test fun standardOgArtworkStaysInsideBanner() = verifyArtwork(1200, 630)
    @Test fun sixteenByNineArtworkKeepsNaturalHeight() = verifyArtwork(640, 360)

    private fun verifyArtwork(imageWidth: Int, imageHeight: Int): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val db = AppDatabase.getInstance(context)
        val id = UUID.randomUUID().toString()
        val domain = "issue123-$id.example"
        val item = BookmarkEntity(id, "Banner containment $id", "https://$domain/article", "Summary",
            createdAt = 1, updatedAt = 1, ogImageUrl = "https://$domain/image.png")
        val bitmap = Bitmap.createBitmap(imageWidth, imageHeight, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLUE)
        val bytes = ByteArrayOutputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            bitmap.recycle()
            it.toByteArray()
        }
        val filename = requireNotNull(item.ogImageKey)
        val width = mutableStateOf(320)
        val previousDownloader = OgImageManager.imageDownloader
        var activity: ComponentActivity? = null
        try {
            db.bookmarkDao().insertOrUpdate(item)
            // Delay resolution so the test also exercises the asynchronous insertion of the banner.
            OgImageManager.imageDownloader = { url ->
                if (url == item.ogImageUrl) { delay(100); bytes } else previousDownloader?.invoke(url)
            }
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
            val host = activity
            instrumentation.runOnMainSync {
                val vm = ViewModelProvider(host)[LibraryViewModel::class.java]
                host.setContent {
                    CompositionLocalProvider(LocalDensity provides Density(1.75f)) {
                    MaterialTheme {
                        Surface {
                            Box(Modifier.width(width.value.dp)) {
                                key(width.value) { BookmarkDetailScreen(id, vm, {}) }
                            }
                        }
                    }
                    }
                }
            }
            withTimeout(10000) {
                while (OgImageManager.getCachedOgImageBitmap(domain, filename) == null) delay(20)
            }
            for (availableWidth in listOf(320, 360, 408, 440, 540, 600)) {
                instrumentation.runOnMainSync { width.value = availableWidth }
                delay(350)
                instrumentation.waitForIdleSync()
                fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                    if (node.text?.toString() == item.title) return node
                    for (index in 0 until node.childCount) {
                        node.getChild(index)?.let { find(it)?.let { match -> return match } }
                    }
                    return null
                }
                val title = withTimeout(10000) {
                    var found: AccessibilityNodeInfo? = null
                    while (found == null) {
                        instrumentation.uiAutomation.clearCache()
                        found = instrumentation.uiAutomation.rootInActiveWindow?.let(::find)
                        if (found == null) delay(20)
                    }
                    found
                }
                val titleBounds = Rect().also(title::getBoundsInScreen)
                val density = 1.75f
                val expectedHeight = minOf((availableWidth - 40) * imageHeight.toFloat() / imageWidth, 300f) * density
                val bottom = titleBounds.top - (16 * density).roundToInt()
                val top = bottom - expectedHeight.roundToInt()
                val screenshot = instrumentation.uiAutomation.takeScreenshot()
                try {
                    var inside = 0
                    var outside = 0
                    var first = screenshot.height
                    var last = 0
                    for (y in 0 until screenshot.height) for (x in 0 until screenshot.width) {
                        val pixel = screenshot.getPixel(x, y)
                        if (Color.blue(pixel) > 245 && Color.red(pixel) < 10 && Color.green(pixel) < 10) {
                            if (y >= top - 2 && y < bottom + 2) inside++ else outside++
                            first = minOf(first, y)
                            last = maxOf(last, y)
                        }
                    }
                    assertTrue("Fixture must render blue artwork", inside > 1000)
                    assertTrue("$imageWidth x $imageHeight at ${availableWidth}dp: $outside image pixels outside [$top, $bottom), painted [$first, $last]", outside == 0)
                    // The 1dp rim covers both ends of the image; compare the remaining pure artwork.
                    val expectedArtworkHeight = expectedHeight - 2 * density
                    assertTrue("$imageWidth x $imageHeight at ${availableWidth}dp: painted ${last - first + 1}, expected $expectedArtworkHeight, bounds $first..$last", kotlin.math.abs((last - first + 1) - expectedArtworkHeight) <= 2)
                    val corner = screenshot.getPixel((20 * density).roundToInt() + 2, top + 2)
                    assertTrue("The rounded top-left corner must exclude artwork", Color.red(corner) > 10 || Color.green(corner) > 10 || Color.blue(corner) < 245)
                    java.io.File(context.cacheDir, "issue123-$imageWidth-$imageHeight.png").outputStream().use {
                        screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                } finally { screenshot.recycle() }
            }
        } finally {
            activity?.let { host -> instrumentation.runOnMainSync { host.finish() } }
            OgImageManager.imageDownloader = previousDownloader
            db.bookmarkDao().deleteById(id)
            OgImageManager.getOgImageFile(context, filename).delete()
            OgImageManager.getDomainLatestImageFile(context, domain).delete()
        }
    }
}
