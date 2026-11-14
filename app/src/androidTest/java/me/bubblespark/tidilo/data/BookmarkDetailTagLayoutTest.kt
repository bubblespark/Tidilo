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
    @Test fun privateOnlyTagsRetainTheClassificationLine() = verifyTags(isPrivate = true)
    @Test fun emptyTagsRetainTheSectionAndEditAction() = verifyTags()
    @Test fun editingTagsUpdatesBreadcrumbAndEntitiesInMemoryAndEnablesSave() = verifyTags(
        category = "Article",
        subcategory = "Tech",
        entities = listOf("InitialEntity"),
        verifyInMemoryEdit = true
    )

    private fun verifyTags(
        category: String? = null,
        subcategory: String? = null,
        isPrivate: Boolean = false,
        entities: List<String> = emptyList(),
        verifyInMemoryEdit: Boolean = false
    ): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val db = AppDatabase.getInstance(context)
        val id = UUID.randomUUID().toString()
        val item = BookmarkEntity(id, "Entity-only spacing", "https://tag-$id.invalid/article", "Summary",
            category = category, subcategory = subcategory, isPrivate = isPrivate,
            entities = entities, createdAt = 1, updatedAt = 1)
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
            fun findNode(node: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
                if (predicate(node)) return node
                repeat(node.childCount) { index -> node.getChild(index)?.let { findNode(it, predicate)?.let { return it } } }
                return null
            }
            suspend fun awaitNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo = withTimeout(10000) {
                var node: AccessibilityNodeInfo? = null
                while (node == null) {
                    instrumentation.uiAutomation.clearCache()
                    node = instrumentation.uiAutomation.rootInActiveWindow?.let { findNode(it, predicate) }
                    if (node == null) delay(20)
                }
                node
            }
            suspend fun boundsWhere(predicate: (AccessibilityNodeInfo) -> Boolean): Rect =
                Rect().also(awaitNode(predicate)::getBoundsInScreen)
            suspend fun bounds(text: String): Rect = boundsWhere { it.text?.toString() == text }
            suspend fun boundsContaining(substring: String): Rect = boundsWhere { it.text?.toString()?.contains(substring) == true }
            suspend fun clickNode(label: String) = withTimeout(10000) {
                while (true) {
                    instrumentation.uiAutomation.clearCache()
                    var target = instrumentation.uiAutomation.rootInActiveWindow?.let {
                        findNode(it) { n -> n.text?.toString() == label || n.contentDescription?.toString() == label }
                    }
                    while (target != null && !target.isClickable) target = target.parent
                    if (target?.isEnabled == true && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) break
                    delay(30)
                }
                delay(150)
            }

            val density = context.resources.displayMetrics.density
            val domainBounds = boundsContaining("tag-$id.invalid")
            val titleBounds = bounds("Entity-only spacing")
            val localizedCategory = category?.let { Category.toLocalized(it, context) }.orEmpty()
            val localizedSubcategory = subcategory?.let { Subcategory.toLocalized(it, context) }.orEmpty()
            val privateLabel = context.getString(R.string.detail_tag_private_label)
            val expectedBreadcrumb = me.bubblespark.tidilo.ui.screens.library.formatClassificationBreadcrumb(
                localizedCategory, localizedSubcategory, isPrivate, privateLabel
            )
            if (expectedBreadcrumb != null) {
                val breadcrumbBounds = bounds(expectedBreadcrumb)
                assertTrue("Classification breadcrumb must sit between domain and title", breadcrumbBounds.top >= domainBounds.bottom - 2 && breadcrumbBounds.bottom <= titleBounds.top + 2)
            } else {
                val domainToTitleGap = (titleBounds.top - domainBounds.bottom) / density
                assertTrue("When classification is empty, title pulls up directly beneath domain without an empty row gap: $domainToTitleGap dp", domainToTitleGap in 2f..18f)
            }

            val belowTitleText = if (entities.isNotEmpty()) {
                entities.first()
            } else {
                context.getString(R.string.detail_add_tags_placeholder)
            }
            val entityBounds = bounds(belowTitleText)
            val titleToEntityGap = (entityBounds.top - titleBounds.bottom) / density
            assertTrue("Entity row sits compactly below the title: $titleToEntityGap dp", titleToEntityGap in 6f..32f)

            val editLabel = context.getString(R.string.detail_edit_tags)
            assertTrue(
                "Tag editing remains available",
                instrumentation.uiAutomation.rootInActiveWindow?.let {
                    findNode(it) { n -> n.contentDescription?.toString() == editLabel } != null
                } == true
            )

            if (verifyInMemoryEdit) {
                val saveLabel = context.getString(R.string.detail_save)
                fun isSaveClickableEnabled(): Boolean {
                    var target = instrumentation.uiAutomation.rootInActiveWindow?.let {
                        findNode(it) { n -> n.contentDescription?.toString() == saveLabel }
                    }
                    while (target != null && !target.isClickable) target = target.parent
                    return target?.isEnabled == true
                }
                assertTrue("Save button starts disabled before edits", !isSaveClickableEnabled())

                clickNode(editLabel)
                // Toggle Private switch inside BookmarkTagEditDialog
                withTimeout(10000) {
                    while (true) {
                        instrumentation.uiAutomation.clearCache()
                        val toggle = instrumentation.uiAutomation.rootInActiveWindow?.let {
                            findNode(it) { n -> n.isCheckable && n.isClickable }
                        }
                        if (toggle != null && toggle.performAction(AccessibilityNodeInfo.ACTION_CLICK)) break
                        delay(30)
                    }
                    delay(150)
                }
                // Add a new entity tag
                clickNode(context.getString(R.string.detail_tag_add_entity))
                val editable = awaitNode { it.isEditable }
                val newEntity = "AddedEntity"
                editable.performAction(
                    AccessibilityNodeInfo.ACTION_SET_TEXT,
                    android.os.Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newEntity)
                    }
                )
                clickNode(context.getString(R.string.common_ok))
                // Confirm parent BookmarkTagEditDialog
                clickNode(context.getString(R.string.common_ok))

                val updatedBreadcrumb = me.bubblespark.tidilo.ui.screens.library.formatClassificationBreadcrumb(
                    localizedCategory, localizedSubcategory, true, privateLabel
                )!!
                val updatedBreadcrumbBounds = bounds(updatedBreadcrumb)
                assertTrue("Updated classification breadcrumb with Private appears above title", updatedBreadcrumbBounds.top < titleBounds.top)
                val addedEntityBounds = bounds(newEntity)
                assertTrue("Added entity chip appears below title", addedEntityBounds.top > titleBounds.bottom)
                assertTrue("Floppy Save button is enabled after confirming tag edits", isSaveClickableEnabled())
            }
        } finally {
            activity?.let { host -> instrumentation.runOnMainSync { host.finish() } }
            db.bookmarkDao().deleteById(id)
        }
    }
}
