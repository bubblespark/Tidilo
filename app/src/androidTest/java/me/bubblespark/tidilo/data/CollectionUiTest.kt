package me.bubblespark.tidilo.data

import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.composable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.data.db.*
import me.bubblespark.tidilo.ui.screens.library.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class CollectionUiTest {
    @Test fun failedInitialMembershipLoadCanClose() = verifyFailedLoadDismissal(false)

    @Test fun closeDuringMembershipLoadExitsImmediately() = verifyFailedLoadDismissal(true)

    private fun verifyFailedLoadDismissal(initiallyLoading: Boolean): Unit = runBlocking {
        val activity = host.launch()
        val shown = mutableStateOf(true)
        val state = mutableStateOf(CollectionMembershipState(loading = initiallyLoading,
            error = if (initiallyLoading) null else "load"))
        val closes = java.util.concurrent.atomic.AtomicInteger()
        try {
            host.main { activity.setContent { MaterialTheme {
                if (shown.value) CollectionMembershipSheet("failed-load", state.value, {},
                    {}, { closes.incrementAndGet(); shown.value = false }, {})
            } } }
            phase = "failed-load heading ($initiallyLoading)"
            await { host.text(it, "Choose collections") }
            delay(700)
            phase = "failed-load dismiss ($initiallyLoading)"
            val dismiss = await { it.config.getOrNull(SemanticsActions.Dismiss) != null }
            host.main { dismiss.config[SemanticsActions.Dismiss].action?.invoke() }
            // Closing never writes memberships, so it need not wait for the initial read.
            withTimeout(5000) { while (shown.value) delay(20) }
            assertEquals(1, closes.get())
        } finally { host.main { activity.finish() } }
    }

    @Test fun editorChoicesExposeExclusiveRolesAndSelection(): Unit = runBlocking {
        val activity = host.launch()
        val state = mutableStateOf(CollectionEditorState(draft = CollectionDraft("Choices")))
        fun option(label: String): SemanticsNode {
            var node = allNodes().first { host.description(it, label) }
            while (node.config.getOrNull(SemanticsActions.OnClick) == null) node = requireNotNull(node.parent)
            return node
        }
        try {
            host.main { activity.setContent { MaterialTheme {
                CollectionEditorContent(state.value, false, { state.value = state.value.copy(draft = it) }, {}, {}, {}, {})
            } } }
            await { host.description(it, "Folder") }
            host.main {
                assertEquals(Role.RadioButton, option("Folder").config.getOrNull(SemanticsProperties.Role))
                assertEquals(true, option("Folder").config.getOrNull(SemanticsProperties.Selected))
                assertEquals(Role.RadioButton, option("Color 1").config.getOrNull(SemanticsProperties.Role))
                assertEquals(2, allNodes().count { it.config.getOrNull(SemanticsProperties.SelectableGroup) == Unit })
            }
            action(await { host.description(it, "Work") })
            withTimeout(5000) {
                var selected = false
                while (!selected) {
                    host.main { selected = option("Work").config.getOrNull(SemanticsProperties.Selected) == true }
                    if (!selected) delay(20)
                }
            }
            action(await { host.description(it, "Color 8") })
            await { host.description(it, "Color 8") && it.config.getOrNull(SemanticsProperties.Selected) == true }
            host.main {
                assertEquals(false, option("Folder").config.getOrNull(SemanticsProperties.Selected))
                assertEquals(false, option("Color 1").config.getOrNull(SemanticsProperties.Selected))
                state.value = state.value.copy(loading = true)
            }
            await { it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton &&
                host.description(it, "Color 1") && it.config.contains(SemanticsProperties.Disabled) }
        } finally { host.main { activity.finish() } }
    }

    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    @Test fun collectionFilterExposesRoleAndActiveState(): Unit = runBlocking {
        val activity = host.launch()
        val mode = mutableStateOf(LibraryFilterMode.NEWEST_FIRST)
        try {
            host.main { activity.setContent { MaterialTheme {
                val rail = androidx.compose.material3.rememberWideNavigationRailState()
                androidx.compose.runtime.LaunchedEffect(Unit) { rail.expand() }
                me.bubblespark.tidilo.ui.navigation.LibraryNavigationRail(rail, mode.value,
                    { mode.value = it }, {}, {}, {}, {})
            } } }
            val label = await { host.text(it, "Collection") }
            val button = generateSequence(label) { it.parent }.first { it.config.getOrNull(SemanticsProperties.Selected) != null }
            assertEquals(false, button.config.getOrNull(SemanticsProperties.Selected))
            action(button)
            await { it.config.getOrNull(SemanticsProperties.Selected) == true &&
                it.children.any { child -> host.text(child, "Collection") } }
            assertEquals(LibraryFilterMode.COLLECTION, mode.value)
        } finally { host.main { activity.finish() } }
    }

    @Test fun collectionCountPillHandlesEnglishAndRetainsJapanese(): Unit = runBlocking {
        val activity = host.launch()
        val count = mutableIntStateOf(1)
        val japanese = mutableStateOf(false)
        try {
            host.main { activity.setContent {
                val context = androidx.compose.ui.platform.LocalContext.current
                val localized = context.createConfigurationContext(android.content.res.Configuration(context.resources.configuration).apply {
                    setLocale(if (japanese.value) java.util.Locale.JAPANESE else java.util.Locale.ENGLISH)
                })
                CompositionLocalProvider(androidx.compose.ui.platform.LocalContext provides localized,
                    androidx.compose.ui.platform.LocalResources provides localized.resources) {
                    MaterialTheme { CollectionCard(CollectionWithCount(CollectionEntity("count", "Count", "", "folder", null, 0, 1, 1), count.intValue), {}) }
                }
            } }
            await { host.text(it, "1 item") }
            host.main { count.intValue = 2 }
            await { host.text(it, "2 items") }
            host.main { japanese.value = true; count.intValue = 1 }
            await { host.text(it, "1件") }
            host.main { count.intValue = 0 }
            await { host.text(it, "0件") }
        } finally { host.main { activity.finish() } }
    }

    @Test fun countBadgesRemainReadableAcrossPaletteAndThemes(): Unit = runBlocking {
        val activity = host.launch()
        val paletteIndex = mutableIntStateOf(0)
        val dark = mutableStateOf(false)
        val dynamic = mutableStateOf(false)
        try {
            host.main { activity.setContent {
                me.bubblespark.tidilo.ui.theme.TidiloTheme(darkTheme = dark.value, dynamicColor = dynamic.value) {
                    CollectionCard(CollectionWithCount(CollectionEntity("contrast",
                        "Badge ${paletteIndex.intValue} ${dark.value} ${dynamic.value}", "", "folder", null,
                        paletteIndex.intValue, 1, 1), 12), {}, Modifier.padding(20.dp))
                }
            } }
            // Screen captures include the Activity launch transition; let it finish first.
            delay(600)
            for (dynamicColor in listOf(false, true)) for (night in listOf(false, true)) for (index in 0..7) {
                host.main { paletteIndex.intValue = index; dark.value = night; dynamic.value = dynamicColor }
                await { host.text(it, "Badge $index $night $dynamicColor") }
                val count = await { host.text(it, "12 items") }
                var bounds = androidx.compose.ui.geometry.Rect.Zero
                host.main { bounds = count.boundsInWindow }
                host.instrumentation.waitForIdleSync()
                // Semantics can update before the compositor presents the new palette.
                delay(80)
                val bitmap = host.instrumentation.uiAutomation.takeScreenshot()
                try {
                    val background = Color(bitmap.getPixel(bounds.right.toInt() + 2, bounds.center.y.toInt())).luminance()
                    var maximumContrast = 1f
                    for (y in bounds.top.toInt() until bounds.bottom.toInt()) for (x in bounds.left.toInt() until bounds.right.toInt()) {
                        val foreground = Color(bitmap.getPixel(x, y)).luminance()
                        maximumContrast = maxOf(maximumContrast,
                            (maxOf(foreground, background) + 0.05f) / (minOf(foreground, background) + 0.05f))
                    }
                    if (maximumContrast < 4.5f) {
                        java.io.File(host.context.cacheDir, "badge-contrast-failure.png").outputStream().use {
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                        }
                    }
                    assertTrue("Badge $index dark=$night dynamic=$dynamicColor bounds=$bounds background=$background has contrast $maximumContrast", maximumContrast >= 4.5f)
                } finally { bitmap.recycle() }
            }
        } finally { host.main { activity.finish() } }
    }

    @Test fun loadFailureOffersRetryWithoutSaveAction(): Unit = runBlocking {
        val activity = host.launch()
        val retries = java.util.concurrent.atomic.AtomicInteger()
        try {
            host.main { activity.setContent { MaterialTheme {
                CollectionEditorContent(CollectionEditorState(draft = CollectionDraft("Draft"), error = "load"),
                    true, {}, {}, {}, {}, {}, onRetry = { retries.incrementAndGet() })
            } } }
            action(await { host.text(it, "Retry") })
            host.main { assertTrue("Existing collections autosave; there is no Save action", allNodes().none { host.text(it, "Save") }) }
            assertEquals(1, retries.get())
        } finally { host.main { activity.finish() } }
    }

    @Test fun collectionCardsExposeButtonRoles(): Unit = runBlocking {
        val activity = host.launch()
        try {
            host.main { activity.setContent { MaterialTheme { Column {
                CollectionCard(CollectionWithCount(CollectionEntity("role", "Role card", "", "folder", null, 0, 1, 1), 0), {})
                AddCollectionDummyCard({})
            } } } }
            for (node in listOf(await { host.text(it, "Role card") },
                await { host.description(it, "New Collection") })) {
                host.main {
                    var button = node
                    while (button.config.getOrNull(SemanticsActions.OnClick) == null) button = requireNotNull(button.parent)
                    assertEquals(Role.Button, button.config.getOrNull(SemanticsProperties.Role))
                }
            }
        } finally { host.main { activity.finish() } }
    }

    @Test fun collectionSubtitleUsesSingularAndPluralForms() {
        val resources = host.context.createConfigurationContext(android.content.res.Configuration(host.context.resources.configuration).apply {
            setLocale(java.util.Locale.ENGLISH)
        }).resources
        val id = me.bubblespark.tidilo.R.plurals.collection_subtitle_count
        assertEquals("0 collections", resources.getQuantityString(id, 0, 0))
        assertEquals("1 collection", resources.getQuantityString(id, 1, 1))
        assertEquals("2 collections", resources.getQuantityString(id, 2, 2))
    }

    private val host = HeaderTestHost()
    private var phase = "cards"
    private fun allNodes(): List<SemanticsNode> {
        fun roots(view: View): List<ViewRootForTest> = when (view) {
            is ViewRootForTest -> listOf(view)
            is ViewGroup -> (0 until view.childCount).flatMap { roots(view.getChildAt(it)) }
            else -> emptyList()
        }
        fun visit(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::visit)
        return WindowInspector.getGlobalWindowViews().flatMap(::roots).flatMap { visit(it.semanticsOwner.unmergedRootSemanticsNode) }
    }
    private fun frontWindowNodes(): List<SemanticsNode> {
        fun roots(view: View): List<ViewRootForTest> = when (view) {
            is ViewRootForTest -> listOf(view)
            is ViewGroup -> (0 until view.childCount).flatMap { roots(view.getChildAt(it)) }
            else -> emptyList()
        }
        fun visit(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::visit)
        val frontView = WindowInspector.getGlobalWindowViews().lastOrNull { view ->
            view.isShown && roots(view).flatMap { visit(it.semanticsOwner.unmergedRootSemanticsNode) }
                .none { it.config.getOrNull(SemanticsProperties.PaneTitle) == "tooltip" }
        } ?: return emptyList()
        return roots(frontView).flatMap { visit(it.semanticsOwner.unmergedRootSemanticsNode) }
    }
    private suspend fun await(predicate: (SemanticsNode) -> Boolean): SemanticsNode = try { withTimeout(8000) {
        var found: SemanticsNode? = null
        while (found == null) { host.main { found = allNodes().firstOrNull(predicate) }; if (found == null) delay(30) }
        requireNotNull(found)
    } } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        host.main { android.util.Log.e("CollectionUiTest", "Phase $phase: " + allNodes().map { it.config.toString() }.joinToString("\n")) }
        throw AssertionError("No matching node during $phase", e)
    }
    private suspend fun awaitFront(predicate: (SemanticsNode) -> Boolean): SemanticsNode = try { withTimeout(8000) {
        var found: SemanticsNode? = null
        while (found == null) { host.main { found = frontWindowNodes().firstOrNull(predicate) }; if (found == null) delay(30) }
        requireNotNull(found)
    } } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        host.main { android.util.Log.e("CollectionUiTest", "Phase $phase (front): " + frontWindowNodes().map { it.config.toString() }.joinToString("\n")) }
        throw AssertionError("No matching front-window node during $phase", e)
    }
    private fun containsText(node: SemanticsNode, value: String): Boolean =
        host.text(node, value) || node.children.any { containsText(it, value) }

    private suspend fun awaitEnabledButton(label: String): SemanticsNode = await { node ->
        node.config.getOrNull(SemanticsProperties.Role) == Role.Button &&
            !node.config.contains(SemanticsProperties.Disabled) &&
            containsText(node, label)
    }

    private fun action(node: SemanticsNode, long: Boolean = false) {
        host.main {
            var target = node
            if (long) {
                while (target.config.getOrNull(SemanticsActions.OnLongClick) == null) target = requireNotNull(target.parent)
                check(target.config[SemanticsActions.OnLongClick].action?.invoke() == true)
            } else {
                while (target.config.getOrNull(SemanticsActions.OnClick) == null) target = requireNotNull(target.parent)
                check(target.config[SemanticsActions.OnClick].action?.invoke() == true)
            }
        }
    }
    private suspend fun capture(name: String) {
        delay(250)
        val bitmap = host.instrumentation.uiAutomation.takeScreenshot()
        try { java.io.File(host.context.cacheDir, "issue174-$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }

    private suspend fun dismissSheet() {
        val node = await { it.config.getOrNull(SemanticsActions.Dismiss) != null }
        host.main { check(node.config[SemanticsActions.Dismiss].action?.invoke() == true) }
        delay(300)
    }

    @Test fun membershipSheetSlidesDownBeforeRemoval(): Unit = runBlocking {
        val db = AppDatabase.getInstance(host.context)
        val id = UUID.randomUUID().toString()
        val title = "Animate ${id.take(8)}"
        val shown = mutableStateOf(true)
        val activity = host.launch()
        var sampler: android.view.Choreographer.FrameCallback? = null
        try {
            db.collectionDao().insert(CollectionEntity(id, title, "", "folder", null, 0, System.currentTimeMillis(), 1))
            db.bookmarkDao().insertOrUpdate(BookmarkEntity(id, "Animate selector", "https://example.com/$id", "", createdAt = 1, updatedAt = 1))
            host.main { activity.setContent { MaterialTheme {
                if (shown.value) CollectionMembershipBottomSheet(id, { shown.value = false }, {})
            } } }
            action(await { host.text(it, title) })
            // The toggle persists immediately; let Room's emission settle before closing.
            withTimeout(5000) { while (db.collectionDao().observeMembershipIds(id).first() != listOf(id)) delay(20) }
            delay(1500)
            val positions = mutableListOf<Float>()
            host.main { positions += allNodes().first { host.text(it, "Choose collections") }.boundsInWindow.top }
            val dismiss = await { it.config.getOrNull(SemanticsActions.Dismiss) != null }
            host.main {
                sampler = object : android.view.Choreographer.FrameCallback {
                    override fun doFrame(frameTimeNanos: Long) {
                        allNodes().firstOrNull { host.text(it, "Choose collections") && it.boundsInWindow.height > 0f }
                            ?.let { positions += it.boundsInWindow.top }
                        if (shown.value) android.view.Choreographer.getInstance().postFrameCallback(this)
                    }
                }
                android.view.Choreographer.getInstance().postFrameCallback(requireNotNull(sampler))
                dismiss.config[SemanticsActions.Dismiss].action?.invoke()
            }
            withTimeout(5000) { while (shown.value) delay(20) }
            host.main { sampler?.let { android.view.Choreographer.getInstance().removeFrameCallback(it) } }
            assertTrue("Sheet must render intermediate downward positions before removal: $positions",
                positions.any { it > positions.first() + 8f })
            assertEquals(listOf(id), db.collectionDao().observeMembershipIds(id).first())
        } finally {
            host.main { sampler?.let { android.view.Choreographer.getInstance().removeFrameCallback(it) }; activity.finish() }
            db.collectionDao().delete(id); db.bookmarkDao().deleteById(id)
        }
    }

    @Test fun membershipTogglesPersistBeforeTheSheetCloses(): Unit = runBlocking {
        val db = AppDatabase.getInstance(host.context)
        val id = UUID.randomUUID().toString()
        val title = "Auto ${id.take(8)}"
        val shown = mutableStateOf(true)
        val activity = host.launch()
        try {
            db.collectionDao().insert(CollectionEntity(id, title, "", "folder", null, 0, System.currentTimeMillis(), 1))
            db.bookmarkDao().insertOrUpdate(BookmarkEntity(id, "Auto assignment", "https://example.com/$id", "", createdAt = 1, updatedAt = 1, isLiked = true))
            host.main { activity.setContent { MaterialTheme {
                if (shown.value) CollectionMembershipBottomSheet(id, { shown.value = false }, {})
            } } }
            action(await { host.text(it, title) })
            withTimeout(5000) { while (db.collectionDao().observeMembershipIds(id).first() != listOf(id)) delay(20) }
            assertTrue("The toggle alone persists; the sheet stays open", shown.value)
            dismissSheet()
            withTimeout(5000) { while (shown.value) delay(20) }
            assertEquals(listOf(id), db.collectionDao().observeMembershipIds(id).first())
            host.main { shown.value = true }
            action(await { host.text(it, title) })
            withTimeout(5000) { while (db.collectionDao().observeMembershipIds(id).first().isNotEmpty()) delay(20) }
            host.instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            withTimeout(5000) { while (shown.value) delay(20) }
            assertTrue(db.collectionDao().observeMembershipIds(id).first().isEmpty())
            assertTrue(requireNotNull(db.bookmarkDao().getBookmarkById(id)).isLiked)
        } finally { host.main { activity.finish() }; db.collectionDao().delete(id); db.bookmarkDao().deleteById(id) }
    }

    @Test fun failedToggleOffersRetryWhileTheSheetStaysOpen(): Unit = runBlocking {
        val db = AppDatabase.getInstance(host.context)
        val id = UUID.randomUUID().toString()
        val trigger = "fail_collection_close_${id.replace("-", "")}"
        val title = "Retry ${id.take(8)}"
        val shown = mutableStateOf(true)
        val activity = host.launch()
        try {
            db.collectionDao().insert(CollectionEntity(id, title, "", "folder", null, 0, System.currentTimeMillis(), 1))
            db.bookmarkDao().insertOrUpdate(BookmarkEntity(id, "Retry assignment", "https://example.com/$id", "", createdAt = 1, updatedAt = 1))
            host.main { activity.setContent { MaterialTheme {
                if (shown.value) CollectionMembershipBottomSheet(id, { shown.value = false }, {})
            } } }
            action(await { host.text(it, title) })
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER $trigger BEFORE INSERT ON bookmark_collection_cross_ref WHEN NEW.bookmarkId = '$id' BEGIN SELECT RAISE(ABORT,'injected close failure'); END")
            action(await { host.text(it, title) })
            val error = await { host.text(it, "Could not save your changes. Please try again.") }
            host.main { assertEquals(LiveRegionMode.Polite, error.config.getOrNull(SemanticsProperties.LiveRegion)) }
            assertTrue("A failed toggle keeps the sheet visible", shown.value)
            assertTrue(db.collectionDao().observeMembershipIds(id).first().isEmpty())
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER $trigger")
            action(await { host.text(it, "Retry") })
            withTimeout(5000) { while (db.collectionDao().observeMembershipIds(id).first() != listOf(id)) delay(20) }
            assertTrue(shown.value)
        } finally {
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS $trigger")
            host.main { activity.finish() }; db.collectionDao().delete(id); db.bookmarkDao().deleteById(id)
        }
    }

    @Test fun saveFailureRetryUsesTheRetryPath(): Unit = runBlocking {
        val activity = host.launch()
        val retries = java.util.concurrent.atomic.AtomicInteger()
        val dismissals = java.util.concurrent.atomic.AtomicInteger()
        try {
            host.main { activity.setContent { MaterialTheme {
                CollectionMembershipContent(CollectionMembershipState(loading = false, error = "save"), {},
                    { retries.incrementAndGet() }, { dismissals.incrementAndGet() }, {}, onClosed = {})
            } } }
            action(await { host.text(it, "Retry") })
            assertEquals(1, retries.get())
            assertEquals(0, dismissals.get())
        } finally { host.main { activity.finish() } }
    }

    @Test fun onlyCreateHasHeaderActionAndSheetHeadersStayFixed(): Unit = runBlocking {
        val activity = host.launch()
        val variant = mutableIntStateOf(0)
        val scale = mutableFloatStateOf(1f)
        val dark = mutableStateOf(false)
        val rows = (1..20).map { CollectionWithCount(CollectionEntity("$it", "Collection $it", "", "folder", null, 0, 1, 1), 0) }
        try {
            host.main { activity.setContent {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale.floatValue)) {
                    MaterialTheme(colorScheme = if (dark.value) darkColorScheme() else lightColorScheme()) {
                        key(variant.intValue) {
                            if (variant.intValue == 0) CollectionEditorContent(
                                CollectionEditorState(draft = CollectionDraft("Draft")), false, {}, {}, {}, {}, {})
                            else CollectionMembershipContent(CollectionMembershipState(rows = rows, loading = false), {}, {}, {}, {}, onClosed = {})
                        }
                    }
                }
            } }
            for (sheet in 0..1) for (night in listOf(false, true)) for (font in listOf(1f, 2f)) {
                host.main { variant.intValue = sheet; scale.floatValue = font; dark.value = night }
                val title = await { host.text(it, if (sheet == 0) "New Collection" else "Choose collections") }
                val save = if (sheet == 0) await { host.text(it, "Create") } else null
                // Settle the sheet's entrance and anchor changes before measuring scroll stability.
                delay(700)
                var beside = false
                var hasCancel = false
                var before = 0f
                host.main {
                    beside = if (save != null) save.boundsInWindow.left >= title.boundsInWindow.right &&
                        kotlin.math.abs(save.boundsInWindow.center.y - title.boundsInWindow.center.y) < 3f
                        else allNodes().none { host.text(it, "Save") || host.text(it, "Create") }
                    hasCancel = allNodes().any { host.text(it, "Cancel") }
                    before = (save ?: title).boundsInWindow.top
                }
                assertTrue("Only the create editor has a title-right action", beside)
                assertFalse("Sheet must not contain Cancel", hasCancel)
                val scroll = await { it.config.getOrNull(SemanticsActions.ScrollBy) != null }
                host.main { scroll.config[SemanticsActions.ScrollBy].action?.invoke(0f, 2000f) }
                delay(400)
                var after = 0f
                host.main { after = (save ?: title).boundsInWindow.top }
                assertEquals("Header action must remain reachable while scrolling", before, after, 1f)
                capture("sheet-header-$sheet-${if (night) "dark" else "light"}-${font.toInt()}")
            }
        } finally { host.main { activity.finish() } }
    }

    @Test fun toolbarCollectionSelectionTracksStoredMembership(): Unit = runBlocking {
        val db = AppDatabase.getInstance(host.context)
        val id = UUID.randomUUID().toString()
        val activity = host.launch()
        val dark = mutableStateOf(false)
        try {
            db.collectionDao().insert(CollectionEntity(id, "Membership", "", "folder", null, 0, 1, 1))
            db.bookmarkDao().insertOrUpdate(BookmarkEntity(id, "Membership toolbar", "https://example.com/$id", "", createdAt = 1, updatedAt = 1))
            host.main {
                val model = androidx.lifecycle.ViewModelProvider(activity)[LibraryViewModel::class.java]
                activity.setContent { MaterialTheme(colorScheme = if (dark.value) darkColorScheme() else lightColorScheme()) {
                    BookmarkDetailScreen(id, model, {})
                } }
            }
            val star = await { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Choose collections") == true }
            fun selected(): Boolean {
                var result = false
                host.main {
                    var node: SemanticsNode? = star
                    while (node != null) {
                        val value = node.config.getOrNull(SemanticsProperties.Selected)
                        if (value != null) { result = value; break }
                        node = node.parent
                    }
                }
                return result
            }
            assertFalse(selected())
            db.collectionDao().insertMembership(BookmarkCollectionCrossRef(id, id))
            withTimeout(5000) { while (!selected()) delay(30) }
            capture("toolbar-selected-light")
            host.main { dark.value = true }
            capture("toolbar-selected-dark")
            db.collectionDao().delete(id)
            withTimeout(5000) { while (selected()) delay(30) }
        } finally { host.main { activity.finish() }; db.collectionDao().delete(id); db.bookmarkDao().deleteById(id) }
    }

    @Test fun actualLibraryEditorRestoresAfterActivityRecreation(): Unit = runBlocking {
        val activity = host.launch()
        var current: androidx.activity.ComponentActivity = activity
        val draft = "Restored ${UUID.randomUUID().toString().take(8)}"
        var oldMode: LibraryFilterMode? = null
        try {
            host.main {
                val model = androidx.lifecycle.ViewModelProvider(activity)[LibraryViewModel::class.java]
                oldMode = model.filterMode.value
                model.setFilterMode(LibraryFilterMode.COLLECTION)
                model.selectCollectionFilter(null)
            }
            action(await { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("New Collection") == true })
            val field = await { it.config.getOrNull(SemanticsProperties.EditableText)?.text == "" && it.config.getOrNull(SemanticsActions.SetText) != null }
            host.main { check(field.config[SemanticsActions.SetText].action?.invoke(AnnotatedString(draft)) == true) }
            await { it.config.getOrNull(SemanticsProperties.EditableText)?.text == draft }
            host.main { activity.recreate() }
            withTimeout(8000) {
                while (current === activity) {
                    host.main {
                        current = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                            .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                            .filterIsInstance<me.bubblespark.tidilo.MainActivity>().firstOrNull { it !== activity } ?: activity
                    }
                    if (current === activity) delay(30)
                }
            }
            delay(300)
            await { it.config.getOrNull(SemanticsProperties.EditableText)?.text == draft }
            dismissSheet()
            assertTrue(AppDatabase.getInstance(host.context).collectionDao().observeCollections(true).first().none { it.collection.title == draft })
        } finally {
            host.main { oldMode?.let { androidx.lifecycle.ViewModelProvider(current)[LibraryViewModel::class.java].setFilterMode(it) }; current.finish() }
        }
    }

    @Test fun createCollectionFromDetailAssignsAndReopensSelectionChecked(): Unit = runBlocking {
        val db = AppDatabase.getInstance(host.context)
        val bookmarkId = UUID.randomUUID().toString()
        val existingAId = UUID.randomUUID().toString()
        val existingDupId = UUID.randomUUID().toString()
        val existingATitle = "Existing ${existingAId.take(6)}"
        val duplicateTitle = "Shared ${existingDupId.take(6)}"
        val cancelledTitle = "Cancelled ${bookmarkId.take(6)}"
        val createdCollectionIds = mutableSetOf(existingAId, existingDupId)
        db.bookmarkDao().insertOrUpdate(
            BookmarkEntity(bookmarkId, "Detail collection flow", "https://example.com/$bookmarkId", "", createdAt = 1, updatedAt = 1, note = "Initial note")
        )
        db.collectionDao().insert(CollectionEntity(existingAId, existingATitle, "", "folder", null, 0, 1, 1))
        db.collectionDao().insert(CollectionEntity(existingDupId, duplicateTitle, "", "folder", null, 1, 2, 2))
        val activity = host.launch()
        val backs = java.util.concurrent.atomic.AtomicInteger()
        try {
            host.main {
                val model = androidx.lifecycle.ViewModelProvider(activity)[LibraryViewModel::class.java]
                activity.setContent {
                    MaterialTheme {
                        BookmarkDetailScreen(
                            bookmarkId = bookmarkId,
                            viewModel = model,
                            onNavigateBack = { backs.incrementAndGet() }
                        )
                    }
                }
            }
            phase = "edit note before collection sheet"
            val note = await { it.config.getOrNull(SemanticsProperties.EditableText)?.text == "Initial note" }
            host.main { check(note.config[SemanticsActions.SetText].action?.invoke(AnnotatedString("Unsaved note")) == true) }

            phase = "open membership sheet"
            action(await { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Choose collections") == true })
            await { host.text(it, "Choose collections") }
            await { host.text(it, "New collection") }

            phase = "check existing collection A"
            action(await { host.text(it, existingATitle) })
            withTimeout(5000) { while (db.collectionDao().observeMembershipIds(bookmarkId).first() != listOf(existingAId)) delay(20) }

            phase = "tap New collection to switch sheets sequentially"
            action(await { host.text(it, "New collection") })
            await { host.text(it, "New Collection") }
            host.main {
                assertTrue("Selection sheet must close before creation sheet is shown", allNodes().none { host.text(it, "Choose collections") })
            }

            phase = "cancel creation sheet and verify return to selection sheet"
            val cancelField = await { it.config.getOrNull(SemanticsProperties.EditableText)?.text == "" && it.config.getOrNull(SemanticsActions.SetText) != null }
            host.main { check(cancelField.config[SemanticsActions.SetText].action?.invoke(AnnotatedString(cancelledTitle)) == true) }
            dismissSheet()
            await { host.text(it, "Choose collections") }
            host.main {
                assertTrue("Creation sheet must close before selection sheet reopens", allNodes().none { host.text(it, "New Collection") })
            }
            assertEquals("Cancelling creation must keep existing memberships intact", listOf(existingAId), db.collectionDao().observeMembershipIds(bookmarkId).first())
            assertTrue("Cancelled draft must not be created", db.collectionDao().observeCollections(true).first().none { it.collection.title == cancelledTitle })

            phase = "re-open creation sheet and create collection with duplicate title"
            action(await { host.text(it, "New collection") })
            await { host.text(it, "New Collection") }
            val titleField = await { it.config.getOrNull(SemanticsProperties.EditableText)?.text == "" && it.config.getOrNull(SemanticsActions.SetText) != null }
            host.main { check(titleField.config[SemanticsActions.SetText].action?.invoke(AnnotatedString(duplicateTitle)) == true) }
            await { it.config.getOrNull(SemanticsProperties.EditableText)?.text == duplicateTitle }
            action(await { host.text(it, "Create") })

            phase = "verify reopened selection sheet has newly created collection checked and existing duplicate unchecked"
            await { host.text(it, "Choose collections") }
            val allMatching = withTimeout(5000) {
                var list = emptyList<CollectionWithCount>()
                while (list.size < 2) {
                    list = db.collectionDao().observeCollections(true).first().filter { it.collection.title == duplicateTitle }
                    if (list.size < 2) delay(20)
                }
                list
            }
            val newlyCreatedId = allMatching.map { it.collection.id }.first { it != existingDupId }
            createdCollectionIds += newlyCreatedId
            val memberships = db.collectionDao().observeMembershipIds(bookmarkId).first().toSet()
            assertEquals(setOf(existingAId, newlyCreatedId), memberships)
            assertFalse("Pre-existing collection with duplicate title must not be checked", existingDupId in memberships)

            withTimeout(5000) {
                var checkedCount = 0
                while (checkedCount != 1) {
                    host.main {
                        val duplicateRows = allNodes().filter { node ->
                            node.config.getOrNull(SemanticsProperties.Role) == Role.Checkbox &&
                                node.children.any { child -> host.text(child, duplicateTitle) }
                        }
                        checkedCount = duplicateRows.count { it.config.getOrNull(SemanticsProperties.ToggleableState) == androidx.compose.ui.state.ToggleableState.On }
                    }
                    if (checkedCount != 1) delay(20)
                }
            }
            withTimeout(5000) { while (requireNotNull(db.bookmarkDao().getBookmarkById(bookmarkId)).note != "Unsaved note") delay(20) }
            assertEquals(0, backs.get())
        } finally {
            host.main { activity.finish() }
            createdCollectionIds.forEach { db.collectionDao().delete(it) }
            db.bookmarkDao().deleteById(bookmarkId)
        }
    }

    @Test fun createFailureInEditorShowsInlineRetryAndRecoversWithoutDuplicate(): Unit = runBlocking {
        val db = AppDatabase.getInstance(host.context)
        val bookmarkId = UUID.randomUUID().toString()
        val title = "RetryCreate ${bookmarkId.take(6)}"
        val trigger = "fail_detail_create_${bookmarkId.replace("-", "")}"
        db.bookmarkDao().insertOrUpdate(BookmarkEntity(bookmarkId, "Retry create bookmark", "https://example.com/$bookmarkId", "", createdAt = 1, updatedAt = 1))
        val activity = host.launch()
        val createdIds = mutableListOf<String>()
        try {
            host.main {
                activity.setContent {
                    MaterialTheme {
                        CollectionEditorBottomSheet(
                            collectionId = null,
                            assignToBookmarkId = bookmarkId,
                            onDismiss = {},
                            onCreated = { createdIds += it }
                        )
                    }
                }
            }
            phase = "enter title for failed create"
            val field = await { it.config.getOrNull(SemanticsProperties.EditableText)?.text == "" && it.config.getOrNull(SemanticsActions.SetText) != null }
            host.main { check(field.config[SemanticsActions.SetText].action?.invoke(AnnotatedString(title)) == true) }
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER $trigger BEFORE INSERT ON bookmark_collection_cross_ref WHEN NEW.bookmarkId = '$bookmarkId' BEGIN SELECT RAISE(ABORT,'injected create assign failure'); END"
            )
            action(await { host.text(it, "Create") })
            phase = "verify error and inline retry visible without manual scroll"
            val error = await { host.text(it, "Could not save your changes. Please try again.") && it.boundsInWindow.height > 0f }
            host.main { assertEquals(LiveRegionMode.Polite, error.config.getOrNull(SemanticsProperties.LiveRegion)) }
            assertTrue("Rollback must prevent orphan collection", db.collectionDao().observeCollections(true).first().none { it.collection.title == title })
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER $trigger")
            action(await { host.text(it, "Retry") && it.boundsInWindow.height > 0f })
            withTimeout(5000) { while (createdIds.isEmpty()) delay(20) }
            assertEquals(1, createdIds.size)
            assertEquals(listOf(createdIds.single()), db.collectionDao().observeMembershipIds(bookmarkId).first())
        } finally {
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS $trigger")
            host.main { activity.finish() }
            createdIds.forEach { db.collectionDao().delete(it) }
            db.collectionDao().observeCollections(true).first().filter { it.collection.title == title }.forEach { db.collectionDao().delete(it.collection.id) }
            db.bookmarkDao().deleteById(bookmarkId)
        }
    }

    @Test fun membershipSheetShowsDisabledNewCollectionWhileLoadingAndLoadError(): Unit = runBlocking {
        val activity = host.launch()
        val state = mutableStateOf(CollectionMembershipState(loading = true))
        val clicks = java.util.concurrent.atomic.AtomicInteger()
        try {
            host.main {
                activity.setContent {
                    MaterialTheme {
                        CollectionMembershipSheet(
                            saveKey = "disabled-new-collection",
                            state = state.value,
                            onToggle = {},
                            onRetry = {},
                            onDismiss = {},
                            onNewCollection = { clicks.incrementAndGet() }
                        )
                    }
                }
            }
            phase = "loading state disables New collection"
            val loadingText = await { host.text(it, "New collection") }
            host.main {
                var button = loadingText
                while (button.config.getOrNull(SemanticsActions.OnClick) == null) button = requireNotNull(button.parent)
                assertTrue(button.config.contains(SemanticsProperties.Disabled))
            }
            phase = "load error disables New collection"
            host.main { state.value = CollectionMembershipState(loading = false, error = "load") }
            await { host.text(it, "Could not load this collection. Please try again.") }
            val errorText = await { host.text(it, "New collection") }
            host.main {
                var button = errorText
                while (button.config.getOrNull(SemanticsActions.OnClick) == null) button = requireNotNull(button.parent)
                assertTrue(button.config.contains(SemanticsProperties.Disabled))
            }
            assertEquals(0, clicks.get())
        } finally { host.main { activity.finish() } }
    }

    @Test fun detailCollectionFlowScrollFocusBackCancelMissingBookmarkAndEmptyState(): Unit = runBlocking {
        val db = AppDatabase.getInstance(host.context)
        val bookmarkId = UUID.randomUUID().toString()
        val createdIds = mutableListOf<String>()
        val baseTime = System.currentTimeMillis() - 100_000L
        val seededIds = (1..25).map { index ->
            val id = UUID.randomUUID().toString()
            createdIds += id
            db.collectionDao().insert(
                CollectionEntity(id, "ScrollItem $index ${id.take(4)}", "", "folder", null, index % 8, baseTime + index, baseTime + index)
            )
            id
        }
        val missingTargetBookmarkId = UUID.randomUUID().toString()
        db.bookmarkDao().insertOrUpdate(
            BookmarkEntity(bookmarkId, "Scroll focus flow", "https://example.com/$bookmarkId", "", createdAt = 1, updatedAt = 1)
        )
        val activity = host.launch()
        try {
            host.instrumentation.setInTouchMode(true)
            // 1. Empty state + missing-bookmark feedback in UI
            val showMissingEditor = mutableStateOf(false)
            val emptyClicks = java.util.concurrent.atomic.AtomicInteger()
            host.main {
                activity.setContent {
                    MaterialTheme {
                        if (!showMissingEditor.value) {
                            CollectionMembershipContent(
                                state = CollectionMembershipState(rows = emptyList(), loading = false),
                                onToggle = {},
                                onRetry = {},
                                onDismiss = {},
                                onNewCollection = { emptyClicks.incrementAndGet() },
                                onClosed = {}
                            )
                        } else {
                            CollectionEditorBottomSheet(
                                collectionId = null,
                                assignToBookmarkId = missingTargetBookmarkId,
                                onDismiss = { showMissingEditor.value = false }
                            )
                        }
                    }
                }
            }
            phase = "verify empty state explanation and New collection button"
            await { host.text(it, "Create a collection to organize this bookmark.") }
            action(await { host.text(it, "New collection") })
            assertEquals(1, emptyClicks.get())

            phase = "verify missing bookmark feedback disables fields/create and omits Retry without orphan collection"
            host.main { showMissingEditor.value = true }
            val missingField = await { it.config.getOrNull(SemanticsProperties.EditableText)?.text == "" && it.config.getOrNull(SemanticsActions.SetText) != null }
            val missingTitle = "MissingTarget ${missingTargetBookmarkId.take(6)}"
            host.main { check(missingField.config[SemanticsActions.SetText].action?.invoke(AnnotatedString(missingTitle)) == true) }
            action(await { host.text(it, "Create") })
            await { host.text(it, "This bookmark is no longer available.") }
            host.main {
                val createText = allNodes().first { host.text(it, "Create") }
                var createButton = createText
                while (createButton.config.getOrNull(SemanticsActions.OnClick) == null) createButton = requireNotNull(createButton.parent)
                assertTrue("Create must be disabled on missing_bookmark", createButton.config.contains(SemanticsProperties.Disabled))
                val editableNodes = allNodes().filter { it.config.getOrNull(SemanticsProperties.EditableText) != null }
                assertTrue(
                    "All text fields must be disabled on missing_bookmark",
                    editableNodes.isNotEmpty() && editableNodes.all {
                        it.config.contains(SemanticsProperties.Disabled) &&
                            it.config.getOrNull(SemanticsActions.SetText) == null
                    }
                )
                val optionNodes = allNodes().filter { it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton }
                assertTrue("Icon/color choices must be disabled on missing_bookmark", optionNodes.isNotEmpty() && optionNodes.all { it.config.contains(SemanticsProperties.Disabled) })
                assertTrue("Retry must not appear on missing_bookmark", allNodes().none { host.text(it, "Retry") })
            }
            assertTrue(db.collectionDao().observeCollections(true).first().none { it.collection.title == missingTitle })

            // 2. Multi-item list: scroll preservation on cancel, Back during OPENING_CREATOR, and focus + scroll-into-view on create return
            host.main {
                val model = androidx.lifecycle.ViewModelProvider(activity)[LibraryViewModel::class.java]
                activity.setContent {
                    MaterialTheme {
                        BookmarkDetailScreen(bookmarkId = bookmarkId, viewModel = model, onNavigateBack = {})
                    }
                }
            }
            phase = "open detail selection sheet with many collections"
            action(await { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Choose collections") == true })
            await { host.text(it, "Choose collections") }
            await { host.text(it, "ScrollItem 25 ${seededIds.last().take(4)}") }
            delay(700)

            val scrollContainer = awaitFront { node ->
                node.config.getOrNull(SemanticsActions.ScrollBy) != null &&
                    (node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.maxValue?.invoke() ?: 0f) > 0f
            }
            host.main { check(scrollContainer.config[SemanticsActions.ScrollBy].action?.invoke(0f, 1200f) == true) }
            delay(500)
            var scrolledOffset = 0f
            host.main {
                scrolledOffset = requireNotNull(
                    frontWindowNodes().first {
                        it.config.getOrNull(SemanticsActions.ScrollBy) != null &&
                            (it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.maxValue?.invoke() ?: 0f) > 0f
                    }.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
                ).value()
            }
            assertTrue("Selection list should have scrolled down: $scrolledOffset", scrolledOffset > 0f)

            phase = "back press during OPENING_CREATOR cancels transition back to visible SELECTING"
            suspend fun awaitUiFrame() = kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
                host.main {
                    android.view.Choreographer.getInstance().postFrameCallback {
                        if (cont.isActive) cont.resumeWith(Result.success(Unit))
                    }
                }
            }
            action(awaitEnabledButton("New collection"))
            withTimeout(5000) {
                while (true) {
                    var closingStarted = false
                    host.main {
                        assertTrue("New Collection must not compose before cancellation", allNodes().none { host.text(it, "New Collection") })
                        val btnText = allNodes().firstOrNull { host.text(it, "New collection") }
                        var btn = btnText
                        while (btn != null && btn.config.getOrNull(SemanticsActions.OnClick) == null) btn = btn.parent
                        closingStarted = btn?.config?.contains(SemanticsProperties.Disabled) == true
                    }
                    if (closingStarted) break
                    awaitUiFrame()
                }
            }
            host.instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            withTimeout(5000) {
                while (true) {
                    var reopened = false
                    host.main {
                        assertTrue("New Collection must never compose when OPENING_CREATOR is cancelled", allNodes().none { host.text(it, "New Collection") })
                        val headingVisible = allNodes().any { host.text(it, "Choose collections") && it.boundsInWindow.height > 0f }
                        val btnText = allNodes().firstOrNull { host.text(it, "New collection") }
                        var btn = btnText
                        while (btn != null && btn.config.getOrNull(SemanticsActions.OnClick) == null) btn = btn.parent
                        reopened = headingVisible && btn != null && !btn.config.contains(SemanticsProperties.Disabled)
                    }
                    if (reopened) break
                    awaitUiFrame()
                }
            }

            phase = "open creator, cancel via dismiss, verify previous scroll offset is preserved"
            action(awaitEnabledButton("New collection"))
            await { host.text(it, "New Collection") }
            dismissSheet()
            await { host.text(it, "Choose collections") }
            await { host.text(it, "ScrollItem 25 ${seededIds.last().take(4)}") }
            delay(400)
            val restoredOffset = run {
                var v = 0f
                host.main {
                    v = requireNotNull(
                        frontWindowNodes().first {
                            it.config.getOrNull(SemanticsActions.ScrollBy) != null &&
                                (it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.maxValue?.invoke() ?: 0f) > 0f
                        }.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
                    ).value()
                }
                v
            }
            assertEquals("Scroll position must be preserved on cancel return", scrolledOffset, restoredOffset, 4f)

            phase = "create new collection while scrolled down and verify scroll into view + focus on return"
            action(awaitEnabledButton("New collection"))
            await { host.text(it, "New Collection") }
            val newTitle = "TopFocused ${bookmarkId.take(6)}"
            val createField = awaitFront {
                it.config.getOrNull(SemanticsProperties.EditableText)?.text == "" &&
                    it.config.getOrNull(SemanticsActions.SetText) != null
            }
            host.main { check(createField.config[SemanticsActions.SetText].action?.invoke(AnnotatedString(newTitle)) == true) }
            // Enter keyboard input mode before submitting via Semantics OnClick so toggleable FocusTargetNode accepts focusRequester.
            host.instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_TAB)
            action(await { host.text(it, "Create") })
            await { host.text(it, "Choose collections") }
            val createdRow = await { node ->
                node.config.getOrNull(SemanticsProperties.Role) == Role.Checkbox &&
                    node.children.any { child -> host.text(child, newTitle) }
            }
            val newId = db.collectionDao().observeCollections(true).first().first { it.collection.title == newTitle }.collection.id
            createdIds += newId
            withTimeout(5000) {
                var focused = false
                var visible = false
                while (!focused || !visible) {
                    host.main {
                        val row = allNodes().firstOrNull { node ->
                            node.config.getOrNull(SemanticsProperties.Role) == Role.Checkbox &&
                                node.children.any { child -> host.text(child, newTitle) }
                        }
                        if (row != null) {
                            visible = row.boundsInWindow.height > 0f && row.boundsInWindow.top >= 0f
                            focused = row.config.getOrNull(SemanticsProperties.Focused) == true
                        }
                    }
                    if (!focused || !visible) delay(20)
                }
            }
            // Space activates toggleable directly because focusRequester targets the single toggleable node.
            host.instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_SPACE)
            withTimeout(5000) {
                var toggledOff = false
                while (!toggledOff) {
                    host.main {
                        val row = allNodes().firstOrNull { node ->
                            node.config.getOrNull(SemanticsProperties.Role) == Role.Checkbox &&
                                node.children.any { child -> host.text(child, newTitle) }
                        }
                        toggledOff = row?.config?.getOrNull(SemanticsProperties.ToggleableState) == androidx.compose.ui.state.ToggleableState.Off
                    }
                    if (!toggledOff) delay(20)
                }
            }
            assertTrue(createdRow.boundsInWindow.height > 0f)
        } finally {
            host.instrumentation.setInTouchMode(true)
            host.main { activity.finish() }
            createdIds.forEach { db.collectionDao().delete(it) }
            db.bookmarkDao().deleteById(bookmarkId)
        }
    }

    @Test fun detailSequentialCreateSurvivesActivityRecreation(): Unit = runBlocking {
        val db = AppDatabase.getInstance(host.context)
        val bookmarkId = UUID.randomUUID().toString()
        val bookmarkTitle = "RecreateDetail ${bookmarkId.take(6)}"
        val draftTitle = "RecreatedCol ${bookmarkId.take(6)}"
        val now = System.currentTimeMillis() + 1_000_000L
        db.bookmarkDao().insertOrUpdate(
            BookmarkEntity(bookmarkId, bookmarkTitle, "https://example.com/$bookmarkId", "", createdAt = now, updatedAt = now)
        )
        var activity: androidx.activity.ComponentActivity = host.launch()
        var oldMode: LibraryFilterMode? = null
        try {
            phase = "navigate to BookmarkDetailScreen in MainActivity"
            host.main {
                val model = androidx.lifecycle.ViewModelProvider(activity)[LibraryViewModel::class.java]
                oldMode = model.filterMode.value
                model.setFilterMode(LibraryFilterMode.NEWEST_FIRST)
            }
            action(await { host.text(it, bookmarkTitle) })
            action(await { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Choose collections") == true })
            await { host.text(it, "Choose collections") }
            action(awaitEnabledButton("New collection"))
            await { host.text(it, "New Collection") }
            val field = awaitFront {
                it.config.getOrNull(SemanticsProperties.EditableText)?.text == "" &&
                    it.config.getOrNull(SemanticsActions.SetText) != null
            }
            host.main { check(field.config[SemanticsActions.SetText].action?.invoke(AnnotatedString(draftTitle)) == true) }
            awaitFront { it.config.getOrNull(SemanticsProperties.EditableText)?.text == draftTitle }

            phase = "recreate MainActivity during CREATING"
            var recreated: androidx.activity.ComponentActivity = activity
            host.main { activity.recreate() }
            withTimeout(8000) {
                while (recreated === activity) {
                    host.main {
                        recreated = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                            .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                            .filterIsInstance<me.bubblespark.tidilo.MainActivity>()
                            .firstOrNull { it !== activity } ?: activity
                    }
                    if (recreated === activity) delay(30)
                }
            }
            activity = recreated
            delay(300)
            await { host.text(it, "New Collection") }
            await { it.config.getOrNull(SemanticsProperties.EditableText)?.text == draftTitle }
            host.main {
                assertTrue("Only creator sheet should be visible after recreation", allNodes().none { host.text(it, "Choose collections") })
            }
            action(await { host.text(it, "Create") })
            await { host.text(it, "Choose collections") }
            withTimeout(5000) {
                var checked = false
                while (!checked) {
                    host.main {
                        checked = allNodes().any { node ->
                            node.config.getOrNull(SemanticsProperties.Role) == Role.Checkbox &&
                                node.config.getOrNull(SemanticsProperties.ToggleableState) == androidx.compose.ui.state.ToggleableState.On &&
                                node.children.any { child -> host.text(child, draftTitle) }
                        }
                    }
                    if (!checked) delay(20)
                }
            }
        } finally {
            host.main {
                oldMode?.let { androidx.lifecycle.ViewModelProvider(activity)[LibraryViewModel::class.java].setFilterMode(it) }
                activity.finish()
            }
            db.collectionDao().observeCollections(true).first().filter { it.collection.title == draftTitle }.forEach { db.collectionDao().delete(it.collection.id) }
            db.bookmarkDao().deleteById(bookmarkId)
        }
    }

    @Test fun pendingEditorCannotBeDismissed(): Unit = runBlocking {
        val activity = host.launch()
        val dismissals = java.util.concurrent.atomic.AtomicInteger()
        try {
            host.main { activity.setContent { MaterialTheme {
                CollectionEditorContent(CollectionEditorState(draft = CollectionDraft("Pending"), pending = true),
                    false, {}, {}, {}, {}, { dismissals.incrementAndGet() })
            } } }
            val dismiss = await { it.config.getOrNull(SemanticsActions.Dismiss) != null }
            host.main { dismiss.config[SemanticsActions.Dismiss].action?.invoke() }
            delay(800)
            assertEquals(0, dismissals.get())
        } finally { host.main { activity.finish() } }
    }

    @Test fun prototypeCardsAndEditorRemainUsableWithLargeText(): Unit = runBlocking {
        val db = AppDatabase.getInstance(host.context)
        val id = UUID.randomUUID().toString()
        val title = "Collection UI ${id.take(8)}"
        val row = CollectionEntity(id, title, "Two lines of description with a long label for layout verification.", "palette", "🎨", 4, 1, 1)
        db.collectionDao().insert(row)
        val activity = host.launch()
        val show = mutableStateOf(false)
        val editing = mutableStateOf<String?>(null)
        val dark = mutableStateOf(false)
        val scale = mutableFloatStateOf(1f)
        try {
            host.main { activity.setContent {
                val density = LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(density, scale.floatValue)) {
                    MaterialTheme(colorScheme = if (dark.value) darkColorScheme() else lightColorScheme()) {
                        Column(Modifier.padding(20.dp)) {
                            CollectionCard(CollectionWithCount(row, 12), onClick = {}, onLongClick = { editing.value = id; show.value = true })
                            AddCollectionDummyCard(onClick = { editing.value = null; show.value = true })
                        }
                        if (show.value) CollectionEditorBottomSheet(editing.value, onDismiss = { show.value = false })
                    }
                }
            } }
            for (night in listOf(false, true)) for (font in listOf(1f, 2f)) {
                host.main { dark.value = night; scale.floatValue = font }
                val node = await { host.text(it, title) }
                var height = 0f
                host.main { height = node.boundsInRoot.height }
                assertTrue(height > 0)
                capture("card-${if (night) "dark" else "light"}-${font.toInt()}")
            }
            action(await { host.text(it, title) }, long = true)
            phase = "edit heading"
            await { host.text(it, "Edit Collection") }
            phase = "edit field"
            val field = await { it.config.getOrNull(SemanticsProperties.EditableText)?.text == title }
            action(field)
            host.main { check(field.config[SemanticsActions.SetText].action?.invoke(AnnotatedString("Edited ${id.take(8)}")) == true) }
            delay(400)
            capture("editor-large-keyboard")
            val scroll = await { it.config.getOrNull(SemanticsActions.ScrollBy) != null }
            host.main { check(scroll.config[SemanticsActions.ScrollBy].action?.invoke(0f, 5000f) == true) }
            delay(600)
            phase = "edit autosave"
            capture("editor-large-bottom")
            // Existing collections autosave; there is no Save action to reach.
            withTimeout(5000) { while (db.collectionDao().getById(id)?.title != "Edited ${id.take(8)}") delay(30) }
            host.main { assertTrue(allNodes().none { host.text(it, "Save") }) }
            dismissSheet()
            withTimeout(5000) { while (show.value) delay(30) }
            phase = "add card"
            action(await { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("New Collection") == true })
            await { host.text(it, "New Collection") }
            phase = "new title"
            val empty = await { it.config.getOrNull(SemanticsProperties.EditableText)?.text == "" && it.config.getOrNull(SemanticsActions.SetText) != null }
            host.main { check(empty.config[SemanticsActions.SetText].action?.invoke(AnnotatedString("Created ${id.take(8)}")) == true) }
            val createScroll = await { it.config.getOrNull(SemanticsActions.ScrollBy) != null }
            host.main { check(createScroll.config[SemanticsActions.ScrollBy].action?.invoke(0f, 5000f) == true) }
            delay(600)
            action(await { host.text(it, "Create") })
            withTimeout(5000) { while (db.collectionDao().observeCollections(true).first().none { it.collection.title == "Created ${id.take(8)}" }) delay(30) }
        } finally {
            host.main { activity.finish() }
            db.collectionDao().observeCollections(true).first().filter { it.collection.id == id || it.collection.title == "Created ${id.take(8)}" }.forEach { db.collectionDao().delete(it.collection.id) }
        }
    }

    @Test fun membershipRowSingleFocusStopDisabledSkipsFocusAndSpaceEnterToggles(): Unit = runBlocking {
        val activity = host.launch()
        val toggled = java.util.Collections.synchronizedList(mutableListOf<String>())
        val state = CollectionMembershipState(
            loading = false,
            rows = listOf(
                CollectionWithCount(CollectionEntity("a", "Row Alpha", "", "folder", null, 0, 1, 1), 0),
                CollectionWithCount(CollectionEntity("b", "Row Beta Disabled", "", "folder", null, 1, 2, 2), 0),
                CollectionWithCount(CollectionEntity("c", "Row Gamma", "", "folder", null, 2, 3, 3), 0)
            ),
            selectedIds = setOf("a"),
            pendingIds = setOf("b")
        )
        try {
            host.main {
                activity.setContent {
                    MaterialTheme {
                        CollectionMembershipContent(
                            state = state,
                            onToggle = { toggled += it },
                            onRetry = {},
                            onDismiss = {},
                            onNewCollection = {},
                            onClosed = {}
                        )
                    }
                }
            }
            await { host.text(it, "Row Gamma") && it.boundsInWindow.height > 0f }
            // Enter keyboard mode and move initial focus onto Row Alpha's single toggleable FocusTargetNode.
            host.instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_TAB)
            host.main {
                var alphaNode: SemanticsNode? = frontWindowNodes().first { node ->
                    node.config.getOrNull(SemanticsProperties.Role) == Role.Checkbox &&
                        containsText(node, "Row Alpha")
                }
                while (alphaNode != null && alphaNode.config.getOrNull(SemanticsActions.RequestFocus) == null) {
                    alphaNode = alphaNode.parent
                }
                check(requireNotNull(alphaNode).config[SemanticsActions.RequestFocus].action?.invoke() == true)
            }

            fun focusedCheckboxTitle(): String? {
                val node = allNodes().firstOrNull {
                    it.config.getOrNull(SemanticsProperties.Role) == Role.Checkbox &&
                        it.config.getOrNull(SemanticsProperties.Focused) == true
                } ?: return null
                return listOf("Row Alpha", "Row Beta Disabled", "Row Gamma").firstOrNull { label ->
                    node.children.any { child -> host.text(child, label) }
                }
            }

            withTimeout(5000) {
                var title: String? = null
                while (title != "Row Alpha") {
                    host.main { title = focusedCheckboxTitle() }
                    if (title != "Row Alpha") delay(20)
                }
            }
            // Space toggles Row Alpha directly (proving focusRequester/Tab landed on .toggleable()).
            host.instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_SPACE)
            withTimeout(5000) { while ("a" !in toggled) delay(20) }

            // Next single Tab must skip both any extra focus stop on Row Alpha and disabled Row Beta, landing directly on Row Gamma.
            host.instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_TAB)
            withTimeout(5000) {
                var title: String? = null
                while (title != "Row Gamma") {
                    host.main {
                        title = focusedCheckboxTitle()
                        assertNotEquals("Disabled row must not receive focus", "Row Beta Disabled", title)
                    }
                    if (title != "Row Gamma") delay(20)
                }
            }
            // Enter toggles Row Gamma directly.
            host.instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_ENTER)
            withTimeout(5000) { while ("c" !in toggled) delay(20) }
            assertEquals(listOf("a", "c"), toggled.toList())
        } finally {
            host.instrumentation.setInTouchMode(true)
            host.main { activity.finish() }
        }
    }

    @Test fun detailSequentialCreateSurvivesLifecycleRestartToCreatedAndBackToResumed(): Unit = runBlocking {
        val db = AppDatabase.getInstance(host.context)
        val bookmarkId = UUID.randomUUID().toString()
        val draftTitle = "LifecycleDraft ${bookmarkId.take(6)}"
        val now = System.currentTimeMillis()
        db.bookmarkDao().insertOrUpdate(
            BookmarkEntity(bookmarkId, "Lifecycle Bookmark", "https://example.com/$bookmarkId", "", createdAt = now, updatedAt = now)
        )
        val activity = host.launch()
        lateinit var lifecycleOwner: androidx.lifecycle.LifecycleOwner
        lateinit var registry: androidx.lifecycle.LifecycleRegistry
        try {
            host.main {
                val model = androidx.lifecycle.ViewModelProvider(activity)[LibraryViewModel::class.java]
                val owner = object : androidx.lifecycle.LifecycleOwner {
                    val reg = androidx.lifecycle.LifecycleRegistry(this)
                    override val lifecycle: androidx.lifecycle.Lifecycle get() = reg
                }
                lifecycleOwner = owner
                registry = owner.reg
                registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED
                activity.setContent {
                    MaterialTheme {
                        CompositionLocalProvider(androidx.lifecycle.compose.LocalLifecycleOwner provides lifecycleOwner) {
                            BookmarkDetailScreen(bookmarkId = bookmarkId, viewModel = model, onNavigateBack = {})
                        }
                    }
                }
            }
            action(await { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Choose collections") == true })
            await { host.text(it, "Choose collections") }
            action(await { host.text(it, "New collection") })
            await { host.text(it, "New Collection") }
            val field = awaitFront {
                it.config.getOrNull(SemanticsProperties.EditableText)?.text == "" &&
                    it.config.getOrNull(SemanticsActions.SetText) != null
            }
            host.main { check(field.config[SemanticsActions.SetText].action?.invoke(AnnotatedString(draftTitle)) == true) }
            awaitFront { it.config.getOrNull(SemanticsProperties.EditableText)?.text == draftTitle }

            // Drop lifecycle to CREATED (cancels bookmarkDetails Flow), then return to RESUMED
            // so bookmarkDetails() re-emits BookmarkDetailState(loading = true, bookmark = null) via onStart.
            host.main { registry.currentState = androidx.lifecycle.Lifecycle.State.CREATED }
            delay(100)
            host.main { registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
            delay(300)

            await { host.text(it, "New Collection") }
            await { it.config.getOrNull(SemanticsProperties.EditableText)?.text == draftTitle }
            action(await { host.text(it, "Create") })
            await { host.text(it, "Choose collections") }
            withTimeout(5000) {
                var checked = false
                while (!checked) {
                    host.main {
                        checked = allNodes().any { node ->
                            node.config.getOrNull(SemanticsProperties.Role) == Role.Checkbox &&
                                node.config.getOrNull(SemanticsProperties.ToggleableState) == androidx.compose.ui.state.ToggleableState.On &&
                                node.children.any { child -> host.text(child, draftTitle) }
                        }
                    }
                    if (!checked) delay(20)
                }
            }

            // If the bookmark is actually deleted while stopped, resuming still exits cleanly to detail_item_not_found.
            host.main { registry.currentState = androidx.lifecycle.Lifecycle.State.CREATED }
            db.bookmarkDao().deleteById(bookmarkId)
            host.main { registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
            await { host.text(it, "Item not found") }
        } finally {
            host.main { activity.finish() }
            db.collectionDao().observeCollections(true).first().filter { it.collection.title == draftTitle }.forEach { db.collectionDao().delete(it.collection.id) }
            db.bookmarkDao().deleteById(bookmarkId)
        }
    }

    @Test fun detailSheetTransitionalRestorationsMountTargetSheetFirst(): Unit = runBlocking {
        val db = AppDatabase.getInstance(host.context)
        val bookmarkId = UUID.randomUUID().toString()
        val existingCollectionId = UUID.randomUUID().toString()
        val existingTitle = "RestoredTarget ${bookmarkId.take(6)}"
        val now = System.currentTimeMillis()
        db.bookmarkDao().insertOrUpdate(
            BookmarkEntity(bookmarkId, "First Frame Bookmark", "https://example.com/$bookmarkId", "", createdAt = now, updatedAt = now)
        )
        db.collectionDao().insert(CollectionEntity(existingCollectionId, existingTitle, "", "folder", null, 0, now, now))

        suspend fun verifyFirstFrameCase(
            label: String,
            savedPhase: DetailCollectionSheetPhase,
            savedCancelTransition: Boolean,
            savedInstanceId: Int,
            savedFocusedId: String?,
            expectedSheetTitle: String,
            expectedInstanceIdAfterSettle: Int
        ) {
            val activity = host.launch()
            val frameClock = BroadcastFrameClock()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frameClock)
            val recomposer = Recomposer(scope.coroutineContext)
            scope.launch { recomposer.runRecomposeAndApplyChanges() }
            var frameTime = 0L

            suspend fun advanceUntil(condition: () -> Boolean) = withTimeout(5000) {
                var reached = false
                while (!reached) {
                    host.main {
                        reached = condition()
                        if (!reached && frameClock.hasAwaiters) {
                            frameTime += 16_000_000
                            frameClock.sendFrame(frameTime)
                        }
                    }
                    if (!reached) delay(10)
                }
            }

            try {
                // Seed and restore through ONE positional rememberSaveable site toggled off/on via showContent
                // so compositeKeyHash matches identically without custom key overrides.
                val sourceRegistry = androidx.compose.runtime.saveable.SaveableStateRegistry(null) { true }
                val restoredRegistryState = mutableStateOf<androidx.compose.runtime.saveable.SaveableStateRegistry?>(null)
                val showContent = mutableStateOf(true)
                var contentPresent = false
                var sourceComposed = false
                lateinit var restoredState: DetailCollectionSheetState
                var restoredHostComposed = false
                var settledHostComposed = false
                val creatorTitle = "New Collection"
                val selectorTitle = "Choose collections"

                fun mountedSheetTitles() = allNodes().mapNotNull { node ->
                    when {
                        host.text(node, creatorTitle) -> creatorTitle
                        host.text(node, selectorTitle) -> selectorTitle
                        else -> null
                    }
                }

                host.main {
                    activity.setContent(parent = recomposer) {
                        MaterialTheme {
                            if (showContent.value) {
                                val restored = restoredRegistryState.value
                                val activeRegistry = restored ?: sourceRegistry
                                DisposableEffect(Unit) {
                                    contentPresent = true
                                    onDispose { contentPresent = false }
                                }
                                CompositionLocalProvider(androidx.compose.runtime.saveable.LocalSaveableStateRegistry provides activeRegistry) {
                                    val sheetState = androidx.compose.runtime.saveable.rememberSaveable(
                                        bookmarkId,
                                        saver = DetailCollectionSheetState.Saver
                                    ) {
                                        if (restored == null) {
                                            DetailCollectionSheetState(
                                                phase = savedPhase,
                                                membershipSheetInstanceId = savedInstanceId,
                                                focusedCollectionId = savedFocusedId,
                                                cancelCreatorTransition = savedCancelTransition
                                            )
                                        } else {
                                            DetailCollectionSheetState()
                                        }
                                    }
                                    if (restored == null) {
                                        SideEffect { sourceComposed = true }
                                    } else {
                                        val settled = sheetState.restoredPhaseSettled
                                        DetailCollectionSheetHost(bookmarkId = bookmarkId, sheetState = sheetState)
                                        SideEffect {
                                            restoredState = sheetState
                                            restoredHostComposed = true
                                            settledHostComposed = settled
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                advanceUntil { sourceComposed }
                lateinit var savedBundle: Map<String, List<Any?>>
                host.main {
                    savedBundle = sourceRegistry.performSave()
                    showContent.value = false
                }
                advanceUntil { !contentPresent }

                val restoredRegistry = androidx.compose.runtime.saveable.SaveableStateRegistry(savedBundle) { true }
                host.main {
                    restoredRegistryState.value = restoredRegistry
                    showContent.value = true
                }
                advanceUntil { restoredHostComposed }
                // Let the dialog window attach without advancing the host to its settled composition.
                await { host.text(it, creatorTitle) || host.text(it, selectorTitle) }
                host.main {
                    assertFalse("$label: settlement recomposed before the first sheet check", settledHostComposed)
                    assertEquals(
                        "$label: only the target sheet must mount in the first restored composition",
                        listOf(expectedSheetTitle),
                        mountedSheetTitles()
                    )
                }

                advanceUntil { settledHostComposed }
                await { host.text(it, expectedSheetTitle) }
                host.main {
                    assertEquals("$label: only the target sheet remains after settlement", listOf(expectedSheetTitle), mountedSheetTitles())
                    assertEquals("$label: instanceId must not churn after settle", expectedInstanceIdAfterSettle, restoredState.membershipSheetInstanceId)
                    assertFalse("$label: cancelCreatorTransition must clear after settle", restoredState.cancelCreatorTransition)
                }
            } finally {
                host.main { activity.finish() }
                recomposer.cancel()
                scope.cancel()
            }
        }

        try {
            verifyFirstFrameCase(
                label = "OPENING_CREATOR(cancel=false)",
                savedPhase = DetailCollectionSheetPhase.OPENING_CREATOR,
                savedCancelTransition = false,
                savedInstanceId = 2,
                savedFocusedId = null,
                expectedSheetTitle = "New Collection",
                expectedInstanceIdAfterSettle = 2
            )
            verifyFirstFrameCase(
                label = "OPENING_CREATOR(cancel=true)",
                savedPhase = DetailCollectionSheetPhase.OPENING_CREATOR,
                savedCancelTransition = true,
                savedInstanceId = 3,
                savedFocusedId = null,
                expectedSheetTitle = "Choose collections",
                expectedInstanceIdAfterSettle = 4
            )
            verifyFirstFrameCase(
                label = "RETURNING_TO_SELECTION",
                savedPhase = DetailCollectionSheetPhase.RETURNING_TO_SELECTION,
                savedCancelTransition = false,
                savedInstanceId = 5,
                savedFocusedId = existingCollectionId,
                expectedSheetTitle = "Choose collections",
                expectedInstanceIdAfterSettle = 5
            )
        } finally {
            db.collectionDao().delete(existingCollectionId)
            db.bookmarkDao().deleteById(bookmarkId)
        }
    }
}
