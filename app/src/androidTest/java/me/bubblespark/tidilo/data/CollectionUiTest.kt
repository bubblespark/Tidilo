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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.data.db.*
import me.bubblespark.tidilo.ui.screens.library.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class CollectionUiTest {
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
    private suspend fun await(predicate: (SemanticsNode) -> Boolean): SemanticsNode = try { withTimeout(8000) {
        var found: SemanticsNode? = null
        while (found == null) { host.main { found = allNodes().firstOrNull(predicate) }; if (found == null) delay(30) }
        requireNotNull(found)
    } } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        host.main { android.util.Log.e("CollectionUiTest", "Phase $phase: " + allNodes().map { it.config.toString() }.joinToString("\n")) }
        throw AssertionError("No matching node during $phase", e)
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
        try {
            db.collectionDao().insert(CollectionEntity(id, title, "", "folder", null, 0, System.currentTimeMillis(), 1))
            db.bookmarkDao().insertOrUpdate(BookmarkEntity(id, "Animate selector", "https://example.com/$id", "", createdAt = 1, updatedAt = 1))
            host.main { activity.setContent { MaterialTheme {
                if (shown.value) CollectionMembershipBottomSheet(id, { shown.value = false }, {})
            } } }
            action(await { host.text(it, title) })
            delay(700)
            val positions = mutableListOf<Float>()
            host.main { positions += allNodes().first { host.text(it, "Choose collections") }.boundsInWindow.top }
            val dismiss = await { it.config.getOrNull(SemanticsActions.Dismiss) != null }
            host.main { dismiss.config[SemanticsActions.Dismiss].action?.invoke() }
            withTimeout(5000) {
                while (shown.value) {
                    host.main {
                        allNodes().firstOrNull { host.text(it, "Choose collections") && it.boundsInWindow.height > 0f }
                            ?.let { positions += it.boundsInWindow.top }
                    }
                    delay(8)
                }
            }
            assertTrue("Sheet must render intermediate downward positions before removal: $positions",
                positions.any { it > positions.first() + 8f })
            assertEquals(listOf(id), db.collectionDao().observeMembershipIds(id).first())
        } finally { host.main { activity.finish() }; db.collectionDao().delete(id); db.bookmarkDao().deleteById(id) }
    }

    @Test fun dismissingMembershipSheetCommitsSelectionAndRemoval(): Unit = runBlocking {
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
            assertTrue(db.collectionDao().observeMembershipIds(id).first().isEmpty())
            dismissSheet()
            withTimeout(5000) { while (shown.value) delay(20) }
            assertEquals(listOf(id), db.collectionDao().observeMembershipIds(id).first())
            host.main { shown.value = true }
            action(await { host.text(it, title) })
            host.instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            withTimeout(5000) { while (shown.value) delay(20) }
            assertTrue(db.collectionDao().observeMembershipIds(id).first().isEmpty())
            assertTrue(requireNotNull(db.bookmarkDao().getBookmarkById(id)).isLiked)
        } finally { host.main { activity.finish() }; db.collectionDao().delete(id); db.bookmarkDao().deleteById(id) }
    }

    @Test fun failedDismissKeepsMembershipSheetOpenForRetry(): Unit = runBlocking {
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
            dismissSheet()
            assertTrue("Failed commit must keep the sheet visible", shown.value)
            await { host.text(it, "Could not save your changes. Please try again.") }
            assertTrue(db.collectionDao().observeMembershipIds(id).first().isEmpty())
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER $trigger")
            action(await { host.text(it, "Retry") })
            withTimeout(5000) { while (shown.value) delay(20) }
            assertEquals(listOf(id), db.collectionDao().observeMembershipIds(id).first())
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

    @Test fun onlyEditorHasSaveAndSheetHeadersStayFixed(): Unit = runBlocking {
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
                                CollectionEditorState(draft = CollectionDraft("Draft")), true, {}, {}, {}, {}, {})
                            else CollectionMembershipContent(CollectionMembershipState(rows = rows, loading = false), {}, {}, {}, {}, onClosed = {})
                        }
                    }
                }
            } }
            for (sheet in 0..1) for (night in listOf(false, true)) for (font in listOf(1f, 2f)) {
                host.main { variant.intValue = sheet; scale.floatValue = font; dark.value = night }
                val title = await { host.text(it, if (sheet == 0) "Edit Collection" else "Choose collections") }
                val save = if (sheet == 0) await { host.text(it, "Save") } else null
                // Settle the sheet's entrance and anchor changes before measuring scroll stability.
                delay(700)
                var beside = false
                var hasCancel = false
                var before = 0f
                host.main {
                    beside = if (save != null) save.boundsInWindow.left >= title.boundsInWindow.right &&
                        kotlin.math.abs(save.boundsInWindow.center.y - title.boundsInWindow.center.y) < 3f
                        else allNodes().none { host.text(it, "Save") }
                    hasCancel = allNodes().any { host.text(it, "Cancel") }
                    before = (save ?: title).boundsInWindow.top
                }
                assertTrue("Only editor has a title-right Save action", beside)
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

    @Test fun openCollectionsProtectsUnsavedDetailDraft(): Unit = runBlocking {
        val db = AppDatabase.getInstance(host.context)
        org.junit.Assume.assumeTrue(db.collectionDao().observeCollections(true).first().isEmpty())
        val id = UUID.randomUUID().toString()
        db.bookmarkDao().insertOrUpdate(BookmarkEntity(id, "Draft navigation", "https://example.com/$id", "", createdAt = 1, updatedAt = 1, note = "Initial note"))
        val activity = host.launch()
        val collections = java.util.concurrent.atomic.AtomicInteger()
        val backs = java.util.concurrent.atomic.AtomicInteger()
        try {
            host.main {
                val model = androidx.lifecycle.ViewModelProvider(activity)[LibraryViewModel::class.java]
                activity.setContent { MaterialTheme { BookmarkDetailScreen(id, model,
                    onNavigateBack = { backs.incrementAndGet() }, onOpenCollections = { collections.incrementAndGet() }) } }
            }
            val note = await { it.config.getOrNull(SemanticsProperties.EditableText)?.text == "Initial note" }
            host.main { check(note.config[SemanticsActions.SetText].action?.invoke(AnnotatedString("Unsaved note")) == true) }
            action(await { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Choose collections") == true })
            action(await { host.text(it, "Open collections") })
            delay(400)
            assertEquals("Navigation must wait for discard approval", 0, collections.get())
            await { host.text(it, "Discard changes?") }
            action(await { host.text(it, "Cancel") })
            await { it.config.getOrNull(SemanticsProperties.EditableText)?.text == "Unsaved note" }
            assertEquals("Initial note", requireNotNull(db.bookmarkDao().getBookmarkById(id)).note)
            action(await { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Choose collections") == true })
            action(await { host.text(it, "Open collections") })
            action(await { host.text(it, "OK") })
            withTimeout(5000) { while (collections.get() != 1) delay(20) }
            assertEquals(0, backs.get())
        } finally { host.main { activity.finish() }; db.bookmarkDao().deleteById(id) }
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
            phase = "edit save"
            val save = await { host.text(it, "Save") }
            var bottom = 0f
            host.main { bottom = save.boundsInWindow.bottom }
            assertTrue(bottom <= activity.resources.displayMetrics.heightPixels)
            capture("editor-large-bottom")
            action(save)
            withTimeout(5000) { while (db.collectionDao().getById(id)?.title != "Edited ${id.take(8)}") delay(30) }
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
}
