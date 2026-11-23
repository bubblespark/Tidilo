package me.bubblespark.tidilo.data

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.data.discover.DiscoverPick
import me.bubblespark.tidilo.data.discover.DiscoverReason
import me.bubblespark.tidilo.data.discover.DiscoverShelf
import me.bubblespark.tidilo.data.discover.DiscoverSnapshot
import me.bubblespark.tidilo.data.discover.MapBubble
import me.bubblespark.tidilo.data.discover.OnThisDayPeriod
import me.bubblespark.tidilo.data.discover.RisingGroup
import me.bubblespark.tidilo.data.engagement.EngagementSurface
import me.bubblespark.tidilo.ui.screens.discover.DiscoverScreen
import me.bubblespark.tidilo.ui.screens.discover.DiscoverSource
import me.bubblespark.tidilo.ui.screens.discover.DiscoverViewModel
import me.bubblespark.tidilo.ui.screens.library.BookmarkItem
import me.bubblespark.tidilo.ui.screens.library.Tag
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Discover shelves rendered from a fake source (no database): layout, announcements, and callbacks. */
class DiscoverShelvesTest {
    private val host = HeaderTestHost()
    private val domain = "${UUID.randomUUID().toString().take(8)}.example"
    private val oldDownloader = OgImageManager.imageDownloader
    private val oldIconDownload = DomainIconManager.httpDownloader
    private val oldDiscovery = DomainIconManager.htmlDiscoverer

    @Before fun images() {
        // Most images are 1200 × 630; "square" URLs return 1:1 art to exercise the mat.
        OgImageManager.imageDownloader = { url ->
            val square = "square" in url
            val bitmap = Bitmap.createBitmap(if (square) 400 else 1200, if (square) 400 else 630, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.HSVToColor(floatArrayOf((url.hashCode() and 0xff) * 1.4f, 0.45f, 0.75f)))
            ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); bitmap.recycle(); it.toByteArray() }
        }
        DomainIconManager.httpDownloader = { DomainIconManager.DownloadResult(null, true, false) }
        DomainIconManager.htmlDiscoverer = { DomainIconManager.HtmlDiscoveryResult(emptyList(), true, false) }
    }

    @After fun restore() {
        OgImageManager.imageDownloader = oldDownloader
        DomainIconManager.httpDownloader = oldIconDownload
        DomainIconManager.htmlDiscoverer = oldDiscovery
    }

    private fun item(id: String, image: Boolean = true, square: Boolean = false, description: String = "") = BookmarkItem(
        id, "Title of $id", "https://$domain/$id", description, Tag.createEmpty(),
        createdAt = System.currentTimeMillis() - 40L * 86_400_000,
        ogImageUrl = if (image) "https://$domain/${if (square) "square" else "wide"}-$id.png" else null
    )
    private fun pick(id: String, reason: DiscoverReason? = null, image: Boolean = true) = DiscoverPick(item(id, image), reason)

    private fun fullSnapshot() = DiscoverSnapshot(
        day = 1, libraryCount = 120,
        hero = DiscoverPick(item("hero", description = "A two-line pull quote from the summary."), DiscoverReason.DormantNeverOpened(94)),
        onThisDay = listOf(pick("otd", DiscoverReason.OnThisDay(OnThisDayPeriod.ONE_YEAR))),
        sleepingGems = listOf(pick("gem0", DiscoverReason.DormantNeverOpened(41)), pick("gem1", DiscoverReason.DormantNeverOpened(52), image = false)),
        rising = listOf(RisingGroup("AI", "ai", listOf(pick("rise", DiscoverReason.Rising(4))))),
        connection = pick("left", DiscoverReason.Connection("Rust")) to pick("right", DiscoverReason.Connection("Rust"), image = false),
        connectionEntity = "Rust",
        map = listOf(MapBubble("Kotlin", "kotlin", 1f), MapBubble("Compose", "compose", 0.4f))
    )

    private class FakeSource(snapshot: DiscoverSnapshot) : DiscoverSource {
        override val privateVisible = MutableStateFlow(true)
        val snapshot = MutableStateFlow(snapshot)
        val justSaved = MutableStateFlow<List<BookmarkItem>>(emptyList())
        val freshRss = MutableStateFlow<List<DiscoverPick>>(emptyList())
        val processing = MutableStateFlow<List<ImportTask>>(emptyList())
        val errors = MutableStateFlow<List<ImportTask>>(emptyList())
        var deck: List<BookmarkItem> = emptyList()
        val impressions = java.util.concurrent.CopyOnWriteArrayList<Pair<String, EngagementSurface>>()
        val flicks = java.util.concurrent.CopyOnWriteArrayList<String>()
        val likes = java.util.concurrent.CopyOnWriteArrayList<Pair<String, EngagementSurface>>()
        override fun snapshot() = snapshot
        override fun justSaved() = justSaved
        override fun freshRss() = freshRss
        override fun processingTasks() = processing
        override fun errorTasks() = errors
        override suspend fun dealDeck() = deck
        override suspend fun recordImpression(bookmarkId: String, surface: EngagementSurface) { impressions += bookmarkId to surface }
        override suspend fun recordDeckFlick(bookmarkId: String, surface: EngagementSurface) { flicks += bookmarkId }
        override suspend fun toggleLike(bookmarkId: String, surface: EngagementSurface): Boolean? {
            likes += bookmarkId to surface
            return true
        }
        override fun seenShelves(): Set<DiscoverShelf>? = DiscoverShelf.entries.toSet()
        override fun saveSeenShelves(shelves: Set<DiscoverShelf>) = Unit
        // Like the repository: suppressed cards and muted entities leave the snapshot until undone.
        private val original = snapshot
        private val hidden = java.util.Collections.synchronizedSet(HashSet<String>())
        private val muted = java.util.Collections.synchronizedSet(HashSet<String>())
        val calls = java.util.concurrent.CopyOnWriteArrayList<String>()
        private fun refilter() {
            snapshot.value = original.copy(
                hero = original.hero?.takeIf { it.item.id !in hidden },
                sleepingGems = original.sleepingGems.filter { it.item.id !in hidden },
                map = original.map.filter { it.skeletonKey !in muted }
            )
        }
        override suspend fun peek(bookmarkId: String) { calls += "peek:$bookmarkId" }
        override suspend fun snooze(bookmarkId: String) { calls += "snooze:$bookmarkId"; hidden += bookmarkId; refilter() }
        override suspend fun neverAgain(bookmarkId: String) { calls += "never:$bookmarkId"; hidden += bookmarkId; refilter() }
        override suspend fun unsuppress(bookmarkId: String) { calls += "unsuppress:$bookmarkId"; hidden -= bookmarkId; refilter() }
        override suspend fun muteTopic(skeletonKey: String) { calls += "mute:$skeletonKey"; muted += skeletonKey; refilter() }
        override suspend fun unmuteTopic(skeletonKey: String) { calls += "unmute:$skeletonKey"; muted -= skeletonKey; refilter() }
        override suspend fun reDeal() { calls += "reDeal"; delay(300) }
        override fun entranceDay(): Long? = 1L // the fixture day: no entrance unless a test asks for it
        override fun saveEntranceDay(day: Long) = Unit
    }

    private class Calls {
        val opened = java.util.concurrent.CopyOnWriteArrayList<Pair<String, DiscoverShelf>>()
        val entities = java.util.concurrent.CopyOnWriteArrayList<String>()
        val retries = java.util.concurrent.CopyOnWriteArrayList<String>()
        val saves = java.util.concurrent.atomic.AtomicInteger()
    }

    private fun show(source: FakeSource, calls: Calls = Calls(), japaneseLightLargeFont: Boolean = false): ComponentActivity {
        val activity = host.launch()
        host.main {
            activity.setContent {
                val base = androidx.compose.ui.platform.LocalContext.current
                val context = if (!japaneseLightLargeFont) base else androidx.compose.runtime.remember(base) {
                    base.createConfigurationContext(android.content.res.Configuration(base.resources.configuration).apply {
                        setLocale(java.util.Locale.JAPAN)
                        fontScale = 2f
                    })
                }
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalContext provides context,
                    androidx.compose.ui.platform.LocalConfiguration provides context.resources.configuration,
                    androidx.compose.ui.platform.LocalResources provides context.resources,
                    androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(
                        context.resources.displayMetrics.density, context.resources.configuration.fontScale)
                ) {
                TidiloTheme(darkTheme = !japaneseLightLargeFont, dynamicColor = false) {
                    // The app draws the canvas behind every screen; this host has no app chrome.
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
                    DiscoverScreen(
                        onOpenNavigation = {},
                        onOpenBookmark = { id, shelf -> calls.opened += id to shelf },
                        onOpenEntity = { calls.entities += it },
                        onSaveUrl = { calls.saves.incrementAndGet() },
                        onRetryImport = { calls.retries += it },
                        viewModel = DiscoverViewModel(source)
                    )
                }
                }
            }
        }
        return activity
    }

    private fun tag(node: SemanticsNode, value: String) = node.config.getOrNull(SemanticsProperties.TestTag) == value
    private fun click(node: SemanticsNode) = host.main { node.config[SemanticsActions.OnClick].action?.invoke() }

    /** Scrolls the Discover list until a node matching [predicate] is composed. */
    private suspend fun find(activity: ComponentActivity, predicate: (SemanticsNode) -> Boolean): SemanticsNode {
        repeat(30) {
            var found: SemanticsNode? = null
            host.main { found = host.nodes(activity).firstOrNull(predicate) }
            found?.let { return it }
            host.scroll(activity, 600f)
            delay(80)
        }
        return host.await(activity, predicate)
    }

    @Test fun emptyLibraryShowsTheWallAndSaveUrl() = runBlocking {
        val calls = Calls()
        val source = FakeSource(fullSnapshot().copy(libraryCount = 0, hero = null, onThisDay = emptyList(), sleepingGems = emptyList(),
            rising = emptyList(), connection = null, map = emptyList()))
        val activity = show(source, calls)
        try {
            host.await(activity) { tag(it, "discover_empty") }
            click(button(activity, "Save URL"))
            assertEquals(1, calls.saves.get())
            host.capture("discover-empty")
        } finally { activity.finish() }
    }

    @Test fun smallLibraryShowsDeckAndHowToSave() = runBlocking {
        val source = FakeSource(fullSnapshot().copy(libraryCount = 10, hero = null, sleepingGems = emptyList(), rising = emptyList(),
            connection = null, map = emptyList(), onThisDay = emptyList()))
        source.deck = listOf(item("d0"), item("d1"))
        source.justSaved.value = listOf(item("new"))
        val activity = show(source)
        try {
            host.await(activity) { tag(it, "discover_shelf_JUST_SAVED") }
            find(activity) { tag(it, "discover_deck") }
            find(activity) { tag(it, "discover_how_to_save") }
            host.main { assertTrue(host.nodes(activity).none { tag(it, "discover_shelf_HERO") }) }
        } finally { activity.finish() }
    }

    @Test fun everyShelfRendersWithAHeadingAndAnnouncesItsReason() = runBlocking {
        val source = FakeSource(fullSnapshot())
        source.deck = listOf(item("d0"), item("d1", square = true))
        source.justSaved.value = listOf(item("new"))
        source.freshRss.value = listOf(pick("rss", DiscoverReason.RssExpiring(2)))
        val activity = show(source)
        try {
            host.await(activity) { tag(it, "discover_shelf_HERO") }
            shell("dumpsys gfxinfo ${host.context.packageName} reset")
            host.capture("discover-hero")
            val hero = host.await(activity) { tag(it, "discover_card_hero") }
            host.main {
                val merged = host.nodes(activity, useUnmergedTree = false).first { tag(it, "discover_card_hero") }
                assertTrue(merged.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { "never opened" in it })
            }
            assertTrue(hero.boundsInRoot.width > 0f)
            val shelves = listOf("ON_THIS_DAY", "JUST_SAVED", "DECK", "SLEEPING_GEMS", "RISING", "FRESH_RSS", "CONNECTIONS", "MAP")
            for ((index, shelf) in shelves.withIndex()) {
                val node = find(activity) { tag(it, "discover_shelf_$shelf") }
                host.scroll(activity, node.boundsInRoot.top - 240f)
                delay(400)
                host.capture("discover-shelf-$index-$shelf")
            }
            host.main {
                val headings = host.nodes(activity).count { it.config.getOrNull(SemanticsProperties.Heading) != null }
                assertTrue("headings: $headings", headings >= 1)
            }
            // Frame smoke check (emulator only; the Pixel 8 p90 budget is measured in Phase 7).
            java.io.File(host.context.cacheDir, "issue137-discover-gfxinfo.txt")
                .writeText(shell("dumpsys gfxinfo ${host.context.packageName}"))
            host.main {
                val rss = host.nodes(activity, useUnmergedTree = false).first { tag(it, "discover_card_rss") }
                assertTrue(rss.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { "Disappears in 2 days" in it })
            }
        } finally { activity.finish() }
    }

    @Test fun japaneseLightThemeAtDoubleFontScaleKeepsShelvesReadable() = runBlocking {
        val source = FakeSource(fullSnapshot())
        source.deck = listOf(item("d0"), item("d1"))
        source.freshRss.value = listOf(pick("rss", DiscoverReason.RssExpiring(2)))
        val activity = show(source, japaneseLightLargeFont = true)
        try {
            host.await(activity) { host.text(it, "今日の一枚") || tag(it, "discover_shelf_HERO") }
            host.capture("discover-ja-light-2x-0-hero")
            for ((index, shelf) in listOf("DECK", "SLEEPING_GEMS", "FRESH_RSS", "MAP").withIndex()) {
                val node = find(activity) { tag(it, "discover_shelf_$shelf") }
                host.scroll(activity, node.boundsInRoot.top - 240f)
                delay(400)
                host.capture("discover-ja-light-2x-${index + 1}-$shelf")
            }
            host.main { assertTrue(host.nodes(activity).any { host.text(it, "シャッフル") }) }
        } finally { activity.finish() }
    }

    @Test fun cardsOpenDetailWithTheirShelfAndEntitiesOpenSearch() = runBlocking {
        val calls = Calls()
        val source = FakeSource(fullSnapshot())
        val activity = show(source, calls)
        try {
            click(host.await(activity) { tag(it, "discover_card_hero") })
            click(find(activity) { tag(it, "discover_card_gem0") })
            click(find(activity) { tag(it, "discover_rising_ai") })
            click(find(activity) { tag(it, "discover_map_kotlin") })
            assertEquals(listOf("hero" to DiscoverShelf.HERO, "gem0" to DiscoverShelf.SLEEPING_GEMS), calls.opened.toList())
            assertEquals(listOf("AI", "Kotlin"), calls.entities.toList())
        } finally { activity.finish() }
    }

    @Test fun runningAndFailedImportsShowInJustSaved() = runBlocking {
        val calls = Calls()
        val source = FakeSource(fullSnapshot().copy(hero = null))
        source.processing.value = listOf(ImportTask(id = "run", url = "https://$domain/run", title = "Running", status = TaskStatus.PROCESSING, statusText = ""))
        source.errors.value = listOf(ImportTask(id = "bad", url = "https://$domain/bad", title = "Broken", status = TaskStatus.ERROR, statusText = ""))
        val activity = show(source, calls)
        try {
            host.await(activity) { tag(it, "discover_task_run") }
            host.capture("discover-saving")
            click(button(activity, "Retry"))
            assertEquals(listOf("bad"), calls.retries.toList())
        } finally { activity.finish() }
    }

    @Test fun deckButtonsAdvanceAndLike() = runBlocking {
        val calls = Calls()
        val source = FakeSource(fullSnapshot().copy(hero = null, onThisDay = emptyList()))
        source.deck = listOf(item("d0"), item("d1"))
        val activity = show(source, calls)
        try {
            click(find(activity) { tag(it, "discover_deck_like") })
            assertEquals(listOf("d0" to EngagementSurface.DISCOVER_DECK), source.likes.toList())
            click(host.await(activity) { tag(it, "discover_deck_next") })
            host.await(activity) { tag(it, "discover_card_d1") }
            click(host.await(activity) { tag(it, "discover_deck_next") })
            host.await(activity) { tag(it, "discover_deck_deal") }
            assertEquals(listOf("d0", "d1"), source.flicks.toList())
        } finally { activity.finish() }
    }

    @Test fun visibleCardRecordsAnImpressionWithItsShelf() = runBlocking {
        val source = FakeSource(fullSnapshot())
        val activity = show(source)
        try {
            host.await(activity) { tag(it, "discover_card_hero") }
            withTimeoutOrFail { source.impressions.any { it == "hero" to EngagementSurface.DISCOVER_HERO } }
        } finally { activity.finish() }
    }

    @Test fun deckFlicksAndOffersAccessibilityActions(): Unit = runBlocking {
        val source = FakeSource(fullSnapshot().copy(hero = null, onThisDay = emptyList()))
        source.deck = listOf(item("d0"), item("d1"), item("d2"))
        val activity = show(source)
        try {
            val card = find(activity) { tag(it, "discover_card_d0") }
            host.main {
                val labels = card.config[SemanticsActions.CustomActions].map { it.label }
                assertEquals(listOf("Next card", "Like"), labels)
                assertEquals("Peek", card.config[SemanticsActions.OnLongClick].label)
                assertEquals("Card 1 of 3", card.config.getOrNull(SemanticsProperties.StateDescription))
            }
            host.main { card.config[SemanticsActions.CustomActions][1].action() }
            withTimeoutOrFail { source.likes.toList() == listOf("d0" to EngagementSurface.DISCOVER_DECK) }
            host.main { card.config[SemanticsActions.CustomActions][0].action() }
            val second = host.await(activity) { tag(it, "discover_card_d1") }
            host.capture("discover-deck-before-drag")
            // A quick drag past 40% of the card's width flicks it.
            val bounds = second.boundsInWindow
            drag(Offset(bounds.left + 40f, bounds.center.y), Offset(bounds.left + 40f + bounds.width * 0.6f, bounds.center.y))
            host.await(activity) { tag(it, "discover_card_d2") }
            withTimeoutOrFail { source.flicks.toList() == listOf("d0", "d1") }
            // A short drag springs back.
            val third = host.await(activity) { tag(it, "discover_card_d2") }.boundsInWindow
            drag(Offset(third.left + 40f, third.center.y), Offset(third.left + 80f, third.center.y), steps = 20, stepMs = 30)
            delay(500)
            assertEquals(listOf("d0", "d1"), source.flicks.toList())
        } finally { activity.finish() }
    }

    @Test fun peekSheetRecordsSnoozesAndUndoes(): Unit = runBlocking {
        val source = FakeSource(fullSnapshot())
        val activity = show(source)
        try {
            val gem = find(activity) { tag(it, "discover_card_gem0") }
            host.main { gem.config[SemanticsActions.OnLongClick].action?.invoke() }
            host.await(activity) { tag(it, "discover_peek_sheet") }
            host.capture("discover-peek-sheet")
            withTimeoutOrFail { "peek:gem0" in source.calls }
            click(button(activity, "Not now"))
            host.awaitAbsent(activity) { tag(it, "discover_card_gem0") }
            click(button(activity, "Undo"))
            find(activity) { tag(it, "discover_card_gem0") }
            assertEquals(listOf("peek:gem0", "snooze:gem0", "unsuppress:gem0"), source.calls.toList())

            val again = find(activity) { tag(it, "discover_card_gem0") }
            host.main { again.config[SemanticsActions.OnLongClick].action?.invoke() }
            click(button(activity, "Don’t show again"))
            host.awaitAbsent(activity) { tag(it, "discover_card_gem0") }
            assertTrue("never:gem0" in source.calls)
        } finally { activity.finish() }
    }

    @Test fun peekLikesAddsToCollectionAndReturnsFocus(): Unit = runBlocking {
        val source = FakeSource(fullSnapshot())
        // Cards take input focus only outside touch mode (keyboard, D-pad), as in any Android app.
        host.instrumentation.setInTouchMode(false)
        val activity = show(source)
        try {
            val hero = host.await(activity) { tag(it, "discover_card_hero") }
            host.main { hero.config[SemanticsActions.OnLongClick].action?.invoke() }
            click(host.await(activity) { tag(it, "discover_peek_like") })
            withTimeoutOrFail { source.likes.toList() == listOf("hero" to EngagementSurface.DISCOVER_PEEK) }
            click(button(activity, "Add to collection"))
            host.await(activity) { host.text(it, "Choose collections") }
            host.awaitAbsent(activity) { tag(it, "discover_peek_sheet") }
            // BACK during the sheet's entrance can be swallowed; let it settle first.
            host.capture("discover-peek-collection")
            host.instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            host.awaitAbsent(activity) { host.text(it, "Choose collections") }
            host.await(activity) { tag(it, "discover_card_hero") && it.config.getOrNull(SemanticsProperties.Focused) == true }
        } finally {
            activity.finish()
            host.instrumentation.setInTouchMode(true)
        }
    }

    @Test fun mapMuteHidesTheBubbleUntilUndo(): Unit = runBlocking {
        val source = FakeSource(fullSnapshot())
        val activity = show(source)
        try {
            val bubble = find(activity) { tag(it, "discover_map_kotlin") }
            host.main {
                assertEquals("Mute topic", bubble.config[SemanticsActions.OnLongClick].label)
                bubble.config[SemanticsActions.OnLongClick].action?.invoke()
            }
            host.awaitAbsent(activity) { tag(it, "discover_map_kotlin") }
            host.capture("discover-map-muted")
            click(button(activity, "Undo"))
            host.await(activity) { tag(it, "discover_map_kotlin") }
            assertEquals(listOf("mute:kotlin", "unmute:kotlin"), source.calls.toList())
        } finally { activity.finish() }
    }

    @Test fun pullingDownReDealsOnceAndFlipsTheSnapshotShelves(): Unit = runBlocking {
        val source = FakeSource(fullSnapshot())
        source.justSaved.value = listOf(item("new"))
        val activity = show(source)
        try {
            val list = host.await(activity) { tag(it, "discover_card_hero") }.boundsInWindow
            drag(Offset(list.center.x, list.top + 40f), Offset(list.center.x, list.top + 900f), steps = 16, stepMs = 20)
            delay(120)
            host.capture("discover-redeal-mid-flip")
            withTimeoutOrFail { source.calls.toList() == listOf("reDeal") }
            delay(800)
            find(activity) { tag(it, "discover_card_new") }
            assertEquals(listOf("reDeal"), source.calls.toList())
        } finally { activity.finish() }
    }

    /** Injects a drag in window coordinates (the test activity is full screen). */
    private suspend fun drag(start: Offset, end: Offset, steps: Int = 6, stepMs: Long = 12) {
        val downTime = android.os.SystemClock.uptimeMillis()
        fun send(action: Int, position: Offset) {
            val event = android.view.MotionEvent.obtain(downTime, android.os.SystemClock.uptimeMillis(), action, position.x, position.y, 0)
            try { host.instrumentation.sendPointerSync(event) } finally { event.recycle() }
        }
        send(android.view.MotionEvent.ACTION_DOWN, start)
        for (step in 1..steps) {
            delay(stepMs)
            send(android.view.MotionEvent.ACTION_MOVE, start + (end - start) * (step / steps.toFloat()))
        }
        send(android.view.MotionEvent.ACTION_UP, end)
    }

    private fun shell(command: String): String =
        host.instrumentation.uiAutomation.executeShellCommand(command).let { fd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText() }
        }

    /** A clickable node whose merged text is [label] (buttons merge their child text). */
    private suspend fun button(activity: ComponentActivity, label: String): SemanticsNode {
        repeat(100) {
            var found: SemanticsNode? = null
            host.main {
                found = host.nodes(activity, useUnmergedTree = false).firstOrNull {
                    it.config.getOrNull(SemanticsActions.OnClick) != null && host.text(it, label)
                }
            }
            found?.let { return it }
            delay(50)
        }
        error("No button $label")
    }

    private suspend fun withTimeoutOrFail(condition: () -> Boolean) {
        repeat(60) { if (condition()) return; delay(50) }
        assertTrue("condition not met", condition())
    }
}
