package me.bubblespark.tidilo.data

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.ui.screens.library.BookmarkDetailScreen
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Samples outside image bounds, proving that the actual detail screen emits colored light. */
class BookmarkDetailLightingTest {
    @get:Rule val composeRule = createComposeRule()

    private data class LightingState(val index: Int, val dark: Boolean, val fontScale: Float = 1f)

    @Test fun detailGlowTracksTheCurrentOgPaletteAndScrolls(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val density = context.resources.displayMetrics.density
        val id = UUID.randomUUID().toString()
        val domain = "radiant-$id.invalid"
        val items = listOf("Amber", "Cyan", "Fallback").map { name ->
            BookmarkEntity("$id-$name", "Radiant $name", "https://$domain/$name", "A long summary. ".repeat(60),
                createdAt = 1, updatedAt = 1, ogImageUrl = if (name == "Fallback") null else "https://$domain/$name.png")
        }
        val caches = OfflineVisualCaches()
        val library = withContext(Dispatchers.IO) { InMemoryLibrary(context).also { it.awaitReady() } }
        try {
            withContext(Dispatchers.IO) {
                library.db.bookmarkDao().insertAll(items)
                items.forEachIndexed { index, item ->
                    val key = item.ogImageKey ?: return@forEachIndexed
                    val color = if (index == 0) android.graphics.Color.rgb(245, 170, 20) else android.graphics.Color.rgb(20, 190, 240)
                    OgImageManager.getOgImageFile(context, key).writeBytes(tinyPng(48, 27, color))
                }
            }
            val vm = library.viewModel()
            val states = listOf(LightingState(0, dark = true), LightingState(2, dark = false), LightingState(1, dark = true, fontScale = 1.3f))
            val state = mutableStateOf(states.first())
            composeRule.setContent {
                val current = state.value
                CompositionLocalProvider(LocalDensity provides Density(density, current.fontScale)) {
                    TidiloTheme(darkTheme = current.dark, dynamicColor = false, blurEnabled = false) {
                        MaterialTheme(colorScheme = if (current.dark) darkColorScheme(background = Color(0xFF141414))
                            else lightColorScheme(background = Color(0xFFF4F4F4))) {
                            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                                BookmarkDetailScreen(items[current.index].id, vm, {})
                            }
                        }
                    }
                }
            }
            val bannerHeight = minOf((context.resources.displayMetrics.widthPixels / density) * 9f / 16f, 300f)
            fun titleTop(item: BookmarkEntity) = composeRule.awaitNode(hasText(item.title)).positionOnScreen.y
            for (current in states) {
                val item = items[current.index]
                composeRule.runOnUiThread { state.value = current }
                composeRule.waitForIdle()
                val fallback = item.ogImageKey == null
                // The title exists before asynchronous image insertion; wait for its final geometry.
                if (!fallback) composeRule.waitUntil(10_000) { titleTop(item) >= (bannerHeight + 16) * density }
                val titleNode = composeRule.awaitNode(hasText(item.title))
                val title = titleNode.positionOnScreen
                val titleCenterY = (title.y + titleNode.size.height / 2f).roundToInt()
                // Sample inside the seamless gradient extension (or beside the title in fallback mode)
                val sampleY = if (fallback) titleCenterY else (title.y.roundToInt() - (8 * density).roundToInt()).coerceAtLeast(10)
                val target = if (fallback) DomainIconManager.generateDomainAccentColor(domain)
                    else if (current.index == 0) Color(0xFFF5AA14) else Color(0xFF14BEF0)
                val channels = listOf(target.red, target.green, target.blue)
                val high = channels.indices.maxBy { channels[it] }
                val low = channels.indices.minBy { channels[it] }
                val difference = sampleScreen(
                    read = { screenshot ->
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
                        total / count
                    },
                    accept = { it > 5 }
                )
                assertTrue("$current ${item.title}: outside-banner glow must follow OG hue, delta=$difference", difference > 5)
                if (item.ogImageKey != null && current.index == 1) {
                    val back = hasContentDescription(context.getString(R.string.common_back))
                    val backBefore = composeRule.awaitNode(back).let { it.positionOnScreen to it.size }
                    val before = instrumentation.uiAutomation.takeScreenshot()
                    try {
                        composeRule.onNode(hasScrollAction() and hasAnyDescendant(hasText(item.title)), useUnmergedTree = true).performTouchInput {
                            val start = Offset(centerX, height * 0.65f)
                            swipe(start, start - Offset(0f, 70 * density), 160)
                        }
                        composeRule.waitUntil(5_000) { titleTop(item) < title.y - 20 * density }
                        composeRule.waitForIdle()
                        val backAfter = composeRule.awaitNode(back).let { it.positionOnScreen to it.size }
                        assertTrue("The floating Back button remains fixed while the image scrolls", backBefore == backAfter)
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
            caches.close()
            library.close()
            items.forEach { item -> item.ogImageKey?.let { OgImageManager.getOgImageFile(context, it).delete() } }
            OgImageManager.clearMemoryCache()
        }
    }

    /** Samples at most 10 screenshots 100ms apart and returns the first accepted (or last) reading. */
    private suspend fun <T> sampleScreen(read: (Bitmap) -> T, accept: (T) -> Boolean): T {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        var attempt = 0
        while (true) {
            val screenshot = automation.takeScreenshot()
            val value = try { read(screenshot) } finally { screenshot.recycle() }
            if (accept(value) || ++attempt == 10) return value
            delay(100)
        }
    }
}
