package me.bubblespark.tidilo.data

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as PixelColor
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelProvider
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.MainActivity
import me.bubblespark.tidilo.ui.screens.library.BookmarkGridCard
import me.bubblespark.tidilo.ui.screens.library.BookmarkItem
import me.bubblespark.tidilo.ui.screens.library.BookmarkItemCard
import me.bubblespark.tidilo.ui.screens.library.Tag
import me.bubblespark.tidilo.ui.screens.library.LocalCardAccentColor
import me.bubblespark.tidilo.ui.screens.library.LibraryScreen
import me.bubblespark.tidilo.ui.screens.library.LibraryViewModel
import me.bubblespark.tidilo.ui.screens.library.LibraryFilterMode
import me.bubblespark.tidilo.ui.screens.library.LibraryViewMode
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.data.db.RssFeedEntity
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks actual painted surfaces and rims instead of testing copies of style constants. */
class BookmarkCardLightingTest {
    @Test fun lightCardsHaveWhiteSurfacesAndDirectionalPrimaryRims() = verifyCards(false)
    @Test fun darkCardsKeepDarkSurfacesAndDirectionalPrimaryRims() = verifyCards(true)
    @Test fun lightCardsFollowScopedBrandChanges() = verifyCards(false, true)
    @Test fun darkCardsFollowScopedBrandChanges() = verifyCards(true, true)

    @Test fun libraryDrillDownCardsUseTheFeedAndDomainAccents(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val db = AppDatabase.getInstance(context)
        val id = UUID.randomUUID().toString()
        val domain = "lighting-$id.invalid"
        val feed = RssFeedEntity(id, "https://feed-$id.invalid/rss", "Lighting feed", enabled = false)
        val manual = BookmarkEntity("$id-manual", "Lighting manual", "https://$domain/article", "Summary", createdAt = 1, updatedAt = 1)
        val rss = BookmarkEntity("$id-rss", "Lighting RSS", "https://other-$id.invalid/article", "Summary",
            createdAt = 2, updatedAt = 2, rssFeedId = id)
        db.rssDao().putFeed(feed)
        db.bookmarkDao().insertAll(listOf(manual, rss))
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
        lateinit var vm: LibraryViewModel
        var previousFilter = LibraryFilterMode.NEWEST_FIRST
        var previousView = LibraryViewMode.LIST
        val dark = mutableStateOf(false)
        try {
            instrumentation.runOnMainSync {
                vm = ViewModelProvider(activity)[LibraryViewModel::class.java]
                previousFilter = vm.filterMode.value
                previousView = vm.viewMode.value
                activity.setContent {
                    MaterialTheme(colorScheme = if (dark.value) darkColorScheme(primary = Color.Gray)
                        else lightColorScheme(primary = Color.Gray)) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { LibraryScreen(vm) }
                    }
                }
            }
            fun find(node: AccessibilityNodeInfo, title: String): AccessibilityNodeInfo? {
                if (node.text?.toString() == title) return node
                for (index in 0 until node.childCount) node.getChild(index)?.let { child ->
                    find(child, title)?.let { return it }
                }
                return null
            }
            for (isDark in listOf(false, true)) for (mode in LibraryViewMode.entries) for (isRss in listOf(false, true)) {
                instrumentation.runOnMainSync {
                    dark.value = isDark
                    vm.setViewMode(mode)
                    vm.setFilterMode(if (isRss) LibraryFilterMode.RSS else LibraryFilterMode.DOMAIN)
                    if (isRss) vm.selectRssFeedFilter(feed) else vm.selectDomainFilter(domain)
                }
                val title = if (isRss) rss.title else manual.title
                val card = withTimeout(10000) {
                    var node: AccessibilityNodeInfo? = null
                    while (node == null) {
                        instrumentation.uiAutomation.clearCache()
                        node = instrumentation.uiAutomation.rootInActiveWindow?.let { find(it, title) }
                        if (node == null) delay(30)
                    }
                    var clickable: AccessibilityNodeInfo = node
                    while (!clickable.isClickable) clickable = requireNotNull(clickable.parent)
                    clickable
                }
                delay(400)
                instrumentation.waitForIdleSync()
                val bounds = Rect().also(card::getBoundsInScreen)
                val screenshot = instrumentation.uiAutomation.takeScreenshot()
                try {
                    File(context.getExternalFilesDir(null), "issue130-library-$mode-$isDark-$isRss.png")
                        .outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    val inset = ((if (mode == LibraryViewMode.GRID) 20 else 28) * context.resources.displayMetrics.density).roundToInt()
                    val rim = screenshot.getPixel(bounds.right - 1, bounds.top + inset)
                    // Without a declared feed image, the publisher supplies the feed accent.
                    val key = if (isRss) requireNotNull(DomainIconManager.feedPublisherDomain(feed)) else domain
                    val expected = DomainIconManager.generateDomainAccentColor(key).toArgb()
                    val actualHsv = FloatArray(3).also { PixelColor.colorToHSV(rim, it) }
                    val expectedHsv = FloatArray(3).also { PixelColor.colorToHSV(expected, it) }
                    val distance = kotlin.math.abs(actualHsv[0] - expectedHsv[0])
                    assertTrue("$mode dark=$isDark rss=$isRss: the rim must match the destination, hue ${actualHsv[0]} vs ${expectedHsv[0]}",
                        minOf(distance, 360f - distance) < 25f && actualHsv[1] > 0.05f)
                } finally { screenshot.recycle() }
            }
        } finally {
            instrumentation.runOnMainSync {
                vm.setFilterMode(previousFilter)
                vm.setViewMode(previousView)
                activity.finish()
            }
            db.bookmarkDao().deleteById(manual.id)
            db.bookmarkDao().deleteById(rss.id)
            db.openHelper.writableDatabase.execSQL("DELETE FROM rss_feeds WHERE id = ?", arrayOf(id))
        }
    }

    private fun verifyCards(dark: Boolean, scoped: Boolean = false): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val density = context.resources.displayMetrics.density
        val grid = mutableStateOf(false)
        val accent = mutableStateOf(Color.Unspecified)
        val measuredBounds = AtomicReference<Rect>()
        val bookmark = BookmarkItem("lighting", "", "", "", Tag.createEmpty(), domain = null)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    val scheme = if (dark) darkColorScheme(primary = Color.Red)
                        else lightColorScheme(primary = Color.Red)
                    MaterialTheme(colorScheme = scheme) {
                        Column(Modifier.fillMaxSize().background(scheme.background).padding(24.dp)) {
                            Box(Modifier.width(300.dp).onGloballyPositioned { coordinates ->
                                val bounds = coordinates.boundsInWindow()
                                measuredBounds.set(Rect(bounds.left.roundToInt(), bounds.top.roundToInt(),
                                    bounds.right.roundToInt(), bounds.bottom.roundToInt()))
                            }) {
                                CompositionLocalProvider(LocalCardAccentColor provides accent.value) {
                                    if (grid.value) BookmarkGridCard(bookmark) else BookmarkItemCard(bookmark)
                                }
                            }
                        }
                    }
                }
            }
            for (isGrid in listOf(false, true)) {
                if (isGrid) measuredBounds.set(null)
                instrumentation.runOnMainSync { grid.value = isGrid }
                // Initial composition also reports bounds when the first state value is unchanged.
                val bounds = withTimeout(10000) {
                    while (measuredBounds.get() == null) delay(20)
                    requireNotNull(measuredBounds.get())
                }
                delay(250)
                instrumentation.waitForIdleSync()
                for (brand in if (scoped) listOf(Color.Blue, Color.Green, Color.Unspecified) else listOf(Color.Unspecified)) {
                    instrumentation.runOnMainSync { accent.value = brand }
                    delay(100)
                    instrumentation.waitForIdleSync()
                    val screenshot = instrumentation.uiAutomation.takeScreenshot()
                    try {
                        File(context.getExternalFilesDir(null), "issue130-${if (dark) "dark" else "light"}-${if (isGrid) "grid" else "list"}.png")
                            .outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        val shadow = screenshot.getPixel(bounds.centerX(), bounds.bottom + (2 * density).roundToInt())
                        val backdrop = screenshot.getPixel(bounds.centerX(), bounds.bottom + (24 * density).roundToInt())
                        val shadowDifference = kotlin.math.abs(PixelColor.red(shadow) - PixelColor.red(backdrop)) +
                            kotlin.math.abs(PixelColor.green(shadow) - PixelColor.green(backdrop)) +
                            kotlin.math.abs(PixelColor.blue(shadow) - PixelColor.blue(backdrop))
                        assertTrue("The shadow must paint outside the rounded card clip", shadowDifference > 2)
                        val surface = screenshot.getPixel(bounds.left + (5 * density).roundToInt(),
                            bounds.bottom - (40 * density).roundToInt())
                        if (dark) {
                            assertTrue("Dark card surfaces must remain dark", PixelColor.red(surface) < 80)
                        } else {
                            assertTrue("Light card surfaces must be crisp white: ${Integer.toHexString(surface)}",
                                PixelColor.red(surface) >= 246 && PixelColor.green(surface) >= 246 && PixelColor.blue(surface) >= 246)
                        }
                        val inset = ((if (isGrid) 20 else 28) * density).roundToInt()
                        fun accentStrength(pixel: Int) = when (brand) {
                            Color.Blue -> PixelColor.blue(pixel) - PixelColor.red(pixel)
                            Color.Green -> PixelColor.green(pixel) - PixelColor.red(pixel)
                            else -> PixelColor.red(pixel) - PixelColor.green(pixel)
                        }
                        val incident = screenshot.getPixel(bounds.right - 1, bounds.top + inset)
                        val opposite = screenshot.getPixel(bounds.left, bounds.bottom - inset)
                        assertTrue("The top-right rim must follow the active accent: ${accentStrength(incident)} / ${accentStrength(opposite)}",
                            accentStrength(incident) > accentStrength(opposite) + 20)
                    } finally { screenshot.recycle() }
                }
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
