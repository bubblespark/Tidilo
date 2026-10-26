package me.bubblespark.tidilo.data

import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.MainActivity
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.ui.screens.library.BookmarkDetailScreen
import me.bubblespark.tidilo.ui.screens.library.LibraryViewModel
import org.junit.Assert.assertTrue
import org.junit.Test

class BookmarkDetailTagLayoutTest {
    @Test fun entityOnlyTagsHaveNoEmptyClassificationGap() = verifyTags(entities = listOf("UniqueEntity"))
    @Test fun categoryOnlyTagsRetainTheClassificationLine() = verifyTags(category = "Article")
    @Test fun subcategoryOnlyTagsRetainTheClassificationLine() = verifyTags(subcategory = "Tech")
    @Test fun nsfwOnlyTagsRetainTheClassificationLine() = verifyTags(nsfw = true)
    @Test fun emptyTagsRetainTheSectionAndEditAction() = verifyTags()

    private fun verifyTags(category: String? = null, subcategory: String? = null, nsfw: Boolean = false,
        entities: List<String> = emptyList()): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val db = AppDatabase.getInstance(context)
        val id = UUID.randomUUID().toString()
        val item = BookmarkEntity(id, "Entity-only spacing", "https://tag-$id.invalid/article", "Summary",
            category = category, subcategory = subcategory, isNsfw = nsfw,
            entities = entities, createdAt = 1, updatedAt = 1, isAiSkipped = true)
        var activity: ComponentActivity? = null
        try {
            db.bookmarkDao().insertOrUpdate(item)
            val host = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
            activity = host
            instrumentation.runOnMainSync {
                val vm = ViewModelProvider(host)[LibraryViewModel::class.java]
                host.setContent { MaterialTheme { Surface { BookmarkDetailScreen(id, vm, {}) } } }
            }
            fun find(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
                if (node.text?.toString() == text) return node
                repeat(node.childCount) { index -> node.getChild(index)?.let { find(it, text)?.let { return it } } }
                return null
            }
            suspend fun bounds(text: String): Rect = withTimeout(10000) {
                var node: AccessibilityNodeInfo? = null
                while (node == null) {
                    instrumentation.uiAutomation.clearCache()
                    node = instrumentation.uiAutomation.rootInActiveWindow?.let { find(it, text) }
                    if (node == null) delay(20)
                }
                Rect().also(node::getBoundsInScreen)
            }
            val firstText = when {
                category != null -> Category.toLocalized(category, context)
                subcategory != null -> Subcategory.toLocalized(subcategory, context)
                nsfw -> "NSFW"
                entities.isNotEmpty() -> entities.first()
                else -> context.getString(R.string.detail_section_summary)
            }
            val entity = bounds(firstText)
            val header = bounds(context.getString(R.string.detail_section_tags))
            val gap = (entity.top - header.bottom) / context.resources.displayMetrics.density
            val maxGap = if (category == null && subcategory == null && !nsfw && entities.isEmpty()) 60f else 26f
            assertTrue("Header gap must exclude empty row and duplicate padding: $gap dp", gap in 8f..maxGap)
            fun hasEdit(node: AccessibilityNodeInfo): Boolean {
                if (node.contentDescription?.toString() == context.getString(R.string.detail_edit_tags)) return true
                repeat(node.childCount) { index -> if (node.getChild(index)?.let(::hasEdit) == true) return true }
                return false
            }
            assertTrue("Tag editing remains available", instrumentation.uiAutomation.rootInActiveWindow?.let(::hasEdit) == true)
        } finally {
            activity?.let { host -> instrumentation.runOnMainSync { host.finish() } }
            db.bookmarkDao().deleteById(id)
        }
    }
}
