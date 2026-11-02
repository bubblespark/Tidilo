package me.bubblespark.tidilo.data

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.MainActivity
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.ui.screens.library.BookmarkDetailScreen
import me.bubblespark.tidilo.ui.screens.library.LibraryViewModel
import org.junit.Assert.assertTrue
import org.junit.Test

/** Samples outside image bounds, proving that the actual detail screen emits colored light. */
class BookmarkDetailLightingTest {
    @Test fun darkDetailTracksTheCurrentOgPalette() = verifyLighting(true)
    @Test fun lightDetailTracksTheCurrentOgPalette() = verifyLighting(false)
    @Test fun enlargedFontDetailTracksTheCurrentOgPalette() = verifyLighting(true, 1.3f)

    private fun verifyLighting(dark: Boolean, fontScale: Float = 1f): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val db = AppDatabase.getInstance(context)
        val id = UUID.randomUUID().toString()
        val domain = "radiant-$id.invalid"
        val items = listOf("Amber", "Cyan", "Fallback").map { name ->
            BookmarkEntity("$id-$name", "Radiant $name", "https://$domain/$name", "A long summary. ".repeat(60),
                createdAt = 1, updatedAt = 1, ogImageUrl = if (name == "Fallback") null else "https://$domain/$name.png")
        }
        val selected = mutableStateOf(items.first().id)
        var activity: ComponentActivity? = null
        try {
            db.bookmarkDao().insertAll(items)
            items.forEachIndexed { index, item ->
                if (item.ogImageKey == null) return@forEachIndexed
                val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(if (index == 0) android.graphics.Color.rgb(245, 170, 20) else android.graphics.Color.rgb(20, 190, 240))
                OgImageManager.getOgImageFile(context, requireNotNull(item.ogImageKey)).outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            val host = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
            activity = host
            instrumentation.runOnMainSync {
                val vm = ViewModelProvider(host)[LibraryViewModel::class.java]
                host.setContent {
                    CompositionLocalProvider(LocalDensity provides Density(context.resources.displayMetrics.density, fontScale)) {
                    MaterialTheme(colorScheme = if (dark) darkColorScheme(background = Color(0xFF141414))
                        else lightColorScheme(background = Color(0xFFF4F4F4))) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                            BookmarkDetailScreen(selected.value, vm, {})
                        }
                    }
                    }
                }
            }
            fun find(node: AccessibilityNodeInfo, title: String): AccessibilityNodeInfo? {
                if (node.text?.toString() == title) return node
                repeat(node.childCount) { index -> node.getChild(index)?.let { find(it, title)?.let { return it } } }
                return null
            }
            items.forEachIndexed { index, item ->
                instrumentation.runOnMainSync { selected.value = item.id }
                val density = context.resources.displayMetrics.density
                val bannerHeight = (context.resources.displayMetrics.widthPixels / density - 40) * 9f / 16f
                val fallback = item.ogImageKey == null
                val title = withTimeout(10000) {
                    var node: AccessibilityNodeInfo? = null
                    while (node == null) {
                        instrumentation.uiAutomation.clearCache()
                        node = instrumentation.uiAutomation.rootInActiveWindow?.let { find(it, item.title) }
                        // The title exists before asynchronous image insertion; wait for its final geometry.
                        if (!fallback && node != null && Rect().also(node::getBoundsInScreen).top < (bannerHeight + 100) * density) node = null
                        if (node == null) delay(20)
                    }
                    Rect().also(node::getBoundsInScreen)
                }
                // After scrolling, the new image-free title can sit beneath the frosted header.
                // Sample beside the URL body instead, so this assertion measures ambient lighting.
                val sampleY = if (fallback) {
                    val urlNode = requireNotNull(instrumentation.uiAutomation.rootInActiveWindow?.let { find(it, item.url) })
                    Rect().also(urlNode::getBoundsInScreen).centerY()
                } else title.top - ((16 + bannerHeight / 2) * density).roundToInt()
                val target = if (fallback) DomainIconManager.generateDomainAccentColor(domain)
                    else if (index == 0) Color(0xFFF5AA14) else Color(0xFF14BEF0)
                val channels = listOf(target.red, target.green, target.blue)
                val high = channels.indices.maxBy { channels[it] }
                val low = channels.indices.minBy { channels[it] }
                var difference = 0
                val deadline = android.os.SystemClock.uptimeMillis() + 5000
                do {
                    val screenshot = instrumentation.uiAutomation.takeScreenshot()
                    try {
                        var total = 0
                        var count = 0
                        val startX = if (fallback) screenshot.width - (12 * density).roundToInt() else (6 * density).roundToInt()
                        val endX = if (fallback) screenshot.width - (6 * density).roundToInt() else (12 * density).roundToInt()
                        for (y in sampleY - 5..sampleY + 5) for (x in startX..endX) {
                            val pixel = screenshot.getPixel(x, y)
                            val rgb = listOf(android.graphics.Color.red(pixel), android.graphics.Color.green(pixel), android.graphics.Color.blue(pixel))
                            total += rgb[high] - rgb[low]
                            count++
                        }
                        difference = total / count
                        File(context.cacheDir, "issue140-$dark-$index.png").outputStream().use {
                            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                    } finally { screenshot.recycle() }
                    if (difference <= 5) delay(30)
                } while (difference <= 5 && android.os.SystemClock.uptimeMillis() < deadline)
                assertTrue("$dark ${item.title}: outside-banner glow must follow OG hue, delta=$difference", difference > 5)
                if (index == 1) {
                    val headerText = context.getString(R.string.detail_title)
                    val headerBefore = Rect().also { rect ->
                        requireNotNull(instrumentation.uiAutomation.rootInActiveWindow?.let { find(it, headerText) }).getBoundsInScreen(rect)
                    }
                    val before = instrumentation.uiAutomation.takeScreenshot()
                    try {
                        val x = before.width * 0.5f
                        val y = before.height * 0.65f
                        val distance = 70 * density
                        val downTime = android.os.SystemClock.uptimeMillis()
                        fun event(action: Int, currentY: Float) {
                            val motion = MotionEvent.obtain(downTime, android.os.SystemClock.uptimeMillis(), action, x, currentY, 0)
                            try { instrumentation.uiAutomation.injectInputEvent(motion, true) } finally { motion.recycle() }
                        }
                        event(MotionEvent.ACTION_DOWN, y)
                        repeat(10) { step ->
                            android.os.SystemClock.sleep(16)
                            event(MotionEvent.ACTION_MOVE, y - distance * (step + 1) / 10f)
                        }
                        event(MotionEvent.ACTION_UP, y - distance)
                        withTimeout(5000) {
                            var moved = false
                            while (!moved) {
                                instrumentation.uiAutomation.clearCache()
                                val node = instrumentation.uiAutomation.rootInActiveWindow?.let { find(it, item.title) }
                                moved = node != null && Rect().also(node::getBoundsInScreen).top < title.top - 20 * density
                                if (!moved) delay(20)
                            }
                        }
                        instrumentation.waitForIdleSync()
                        val headerAfter = Rect().also { rect ->
                            requireNotNull(instrumentation.uiAutomation.rootInActiveWindow?.let { find(it, headerText) }).getBoundsInScreen(rect)
                        }
                        assertTrue("The independent header remains fixed while the image scrolls", headerBefore == headerAfter)
                        val after = instrumentation.uiAutomation.takeScreenshot()
                        try {
                            var changed = 0
                            // Outside the banner, so image motion alone cannot satisfy this assertion.
                            for (sample in sampleY..sampleY + (100 * density).roundToInt() step 4) {
                                val px = (8 * density).roundToInt()
                                if (before.getPixel(px, sample) != after.getPixel(px, sample)) changed++
                            }
                            assertTrue("The painted ambient light follows scrolling content", changed > 10)
                        } finally { after.recycle() }
                    } finally { before.recycle() }
                }
            }
        } finally {
            activity?.let { host -> instrumentation.runOnMainSync { host.finish() } }
            items.forEach { item ->
                db.bookmarkDao().deleteById(item.id)
                item.ogImageKey?.let { OgImageManager.getOgImageFile(context, it).delete() }
            }
            OgImageManager.clearMemoryCache()
        }
    }
}
