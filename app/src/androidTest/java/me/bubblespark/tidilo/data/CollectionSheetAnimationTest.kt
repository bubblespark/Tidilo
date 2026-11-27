package me.bubblespark.tidilo.data

import android.view.Choreographer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.data.db.CollectionEntity
import me.bubblespark.tidilo.ui.screens.library.CollectionMembershipBottomSheet
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** The membership sheet uses the app database, so rows carry unique IDs and are removed afterwards. */
class CollectionSheetAnimationTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun membershipSheetSlidesDownBeforeRemoval(): Unit = runBlocking {
        val db = AppDatabase.getInstance(InstrumentationRegistry.getInstrumentation().targetContext)
        val id = UUID.randomUUID().toString()
        val title = "Animate ${id.take(8)}"
        val shown = mutableStateOf(true)
        var sampler: Choreographer.FrameCallback? = null
        try {
            db.withTransaction {
                db.collectionDao().insert(CollectionEntity(id, title, "", "folder", null, 0, System.currentTimeMillis(), 1))
                db.bookmarkDao().insertOrUpdate(BookmarkEntity(id, "Animate selector", "https://example.com/$id", "", createdAt = 1, updatedAt = 1))
            }
            composeRule.setContent {
                TidiloTheme(darkTheme = false, dynamicColor = false, blurEnabled = false) {
                    if (shown.value) CollectionMembershipBottomSheet(id, { shown.value = false }, {})
                }
            }
            composeRule.clickNode(composeRule.awaitNode(hasText(title)))
            withTimeout(5_000) { db.collectionDao().observeMembershipIds(id).first { it == listOf(id) } }
            composeRule.waitForIdle()
            // layoutInfo is the live layout node, so it can be read on each frame without a rule call.
            val heading = composeRule.awaitNode(hasText("Choose collections")).layoutInfo
            val positions = mutableListOf(heading.coordinates.positionInWindow().y)
            val dismiss = composeRule.awaitNode(SemanticsMatcher("dismissable") { it.config.getOrNull(SemanticsActions.Dismiss) != null })
            composeRule.runOnUiThread {
                sampler = object : Choreographer.FrameCallback {
                    override fun doFrame(frameTimeNanos: Long) {
                        if (heading.isAttached) positions += heading.coordinates.positionInWindow().y
                        if (shown.value) Choreographer.getInstance().postFrameCallback(this)
                    }
                }
                Choreographer.getInstance().postFrameCallback(requireNotNull(sampler))
                dismiss.config[SemanticsActions.Dismiss].action?.invoke()
            }
            composeRule.waitUntil(5_000) { !shown.value }
            // Stop sampling and copy on the UI thread, where the frame callback appends.
            val samples = composeRule.runOnUiThread {
                sampler?.let { Choreographer.getInstance().removeFrameCallback(it) }
                positions.toList()
            }
            assertTrue("Sheet must render intermediate downward positions before removal: $samples",
                samples.any { it > samples.first() + 8f })
            assertEquals(listOf(id), db.collectionDao().observeMembershipIds(id).first())
        } finally {
            composeRule.runOnUiThread { sampler?.let { Choreographer.getInstance().removeFrameCallback(it) } }
            withContext(NonCancellable + Dispatchers.IO) {
                db.withTransaction { db.collectionDao().delete(id); db.bookmarkDao().deleteById(id) }
            }
        }
    }
}
