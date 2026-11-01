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
