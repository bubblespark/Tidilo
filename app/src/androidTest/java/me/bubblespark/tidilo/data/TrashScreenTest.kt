package me.bubblespark.tidilo.data

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.config.AppTheme
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.data.db.TrashBookmarkRow
import me.bubblespark.tidilo.data.db.TrashedBookmarkEntity
import me.bubblespark.tidilo.ui.screens.trash.TrashAction
import me.bubblespark.tidilo.ui.screens.trash.TrashConfirmation
import me.bubblespark.tidilo.ui.screens.trash.TrashContent
import me.bubblespark.tidilo.ui.screens.trash.TrashStore
import me.bubblespark.tidilo.ui.screens.trash.TrashUiState
import me.bubblespark.tidilo.ui.screens.trash.TrashViewModel
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TrashScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun darkListUsesThemeForegroundWithoutParentSurface() {
        val row = TrashBookmarkRow(
            TrashedBookmarkEntity(
                "dark", "Dark title", "https://example.com", "",
                createdAt = 1, updatedAt = 1, note = "", isPrivate = false, trashedAt = 2
            ),
            false
        )
        var foreground = Color.Unspecified
        composeRule.setContent {
            TidiloTheme(darkTheme = true, dynamicColor = false, blurEnabled = false) {
                foreground = MaterialTheme.colorScheme.onBackground
                CompositionLocalProvider(LocalContentColor provides Color.Black) {
                    TrashContent(TrashUiState(loading = false, rows = listOf(row)), {}, { _, _ -> }, {}, {}, {})
                }
            }
        }
        composeRule.waitUntil(5000) {
            composeRule.onAllNodes(hasText("Dark title"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        val title = composeRule.onNodeWithText("Dark title", useUnmergedTree = true).fetchSemanticsNode()
        val layout = mutableListOf<TextLayoutResult>()
        assertTrue(title.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layout) == true)
        assertEquals(foreground, layout.single().layoutInput.style.color)
    }

    private fun fixture(block: suspend (AppDatabase) -> Unit) = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val repository = BookmarkRepository(db, autoInitialize = false)
            for (id in listOf("one", "two")) {
                db.bookmarkDao().insertOrUpdate(
                    BookmarkEntity(id, "Deleted $id", "https://example.com/$id", "Summary", createdAt = 1, updatedAt = 1)
                )
                repository.moveToTrash(id, if (id == "one") 3 else 2, 100)
            }
            val store = object : TrashStore {
                override fun observe(showPrivate: Boolean) = db.trashDao().observeTrash(showPrivate)
                override suspend fun restore(id: String) = repository.restoreFromTrash(id)
                override suspend fun delete(id: String) = repository.deleteFromTrash(id)
                override suspend fun retry() = Unit
            }
            val model = TrashViewModel(store, SavedStateHandle(), MutableStateFlow(true), scope)
            composeRule.setContent {
                TidiloTheme(blurEnabled = false, dynamicColor = false) {
                    val state by model.uiState.collectAsStateWithLifecycle()
                    Surface(color = MaterialTheme.colorScheme.background) {
                        TrashContent(
                            state, {}, model::requestAction, model::dismissConfirmation,
                            model::confirmAction, model::retry
                        )
                    }
                }
            }
            composeRule.waitUntil(5000) {
                composeRule.onAllNodes(hasText("Deleted one"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            block(db)
        } finally {
            scope.cancel()
            db.close()
        }
    }

    private fun openConfirmation(id: String, resource: Int) {
        composeRule.waitUntil(5000) {
            composeRule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "trash_confirm"), useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag("trash_actions:$id", useUnmergedTree = true).performClick()
        val label = context.getString(resource)
        composeRule.waitUntil(5000) {
            composeRule.onAllNodes(hasText(label), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(label, useUnmergedTree = true).performClick()
        composeRule.waitUntil(5000) {
            composeRule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "trash_confirm"), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private suspend fun awaitTrashDeleted(db: AppDatabase, id: String) {
        withTimeout(5000) {
            while (withContext(Dispatchers.IO) { db.trashDao().getById(id) } != null) {
                delay(100)
            }
        }
    }

    @Test
    fun bothActionsRequireConfirmation() = fixture { db ->
        openConfirmation("one", R.string.trash_restore)
        composeRule.waitUntil(5000) {
            composeRule.onAllNodes(hasText(context.getString(R.string.trash_restore_title)), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertNotNull(db.trashDao().getById("one"))
        assertNull(db.bookmarkDao().getBookmarkById("one"))
        composeRule.onNodeWithText(context.getString(R.string.common_cancel), useUnmergedTree = true).performClick()
        composeRule.waitForIdle()
        assertNotNull(db.trashDao().getById("one"))

        openConfirmation("one", R.string.trash_restore)
        composeRule.onNodeWithTag("trash_confirm", useUnmergedTree = true).performClick()
        awaitTrashDeleted(db, "one")
        assertNotNull(db.bookmarkDao().getBookmarkById("one"))

        openConfirmation("two", R.string.common_delete)
        composeRule.waitUntil(5000) {
            composeRule.onAllNodes(hasText(context.getString(R.string.trash_delete_title)), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(context.getString(R.string.common_cancel), useUnmergedTree = true).performClick()
        composeRule.waitForIdle()
        assertNotNull(db.trashDao().getById("two"))

        openConfirmation("two", R.string.common_delete)
        composeRule.onNodeWithTag("trash_confirm", useUnmergedTree = true).performClick()
        awaitTrashDeleted(db, "two")
        assertNull(db.bookmarkDao().getBookmarkById("two"))
        composeRule.waitUntil(5000) {
            composeRule.onAllNodes(hasText(context.getString(R.string.trash_empty)), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun duplicatesHaveLabelAndDisabledRestore() = fixture { db ->
        db.bookmarkDao().insertOrUpdate(
            BookmarkEntity("existing", "Existing", "https://example.com/one", "", createdAt = 1, updatedAt = 1, isPrivate = true)
        )
        val duplicateLabel = context.getString(R.string.trash_duplicate)
        composeRule.waitUntil(5000) {
            composeRule.onAllNodes(hasText(duplicateLabel), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("trash_actions:one", useUnmergedTree = true).performClick()
        val restoreLabel = context.getString(R.string.trash_restore)
        composeRule.waitUntil(5000) {
            composeRule.onAllNodes(hasText(restoreLabel), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        val restoreNode = composeRule.onNodeWithText(restoreLabel, useUnmergedTree = true).fetchSemanticsNode()
        assertTrue(generateSequence(restoreNode) { it.parent }.any { it.config.getOrNull(SemanticsProperties.Disabled) != null })

        composeRule.onNodeWithText(context.getString(R.string.common_delete), useUnmergedTree = true).performClick()
        val cancelLabel = context.getString(R.string.common_cancel)
        composeRule.waitUntil(5000) {
            composeRule.onAllNodes(hasText(cancelLabel), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(cancelLabel, useUnmergedTree = true).performClick()
        composeRule.waitForIdle()

        val titleNode = composeRule.onNodeWithText("Deleted one", useUnmergedTree = true).fetchSemanticsNode()
        assertFalse(generateSequence(titleNode) { it.parent }.any { it.config.getOrNull(SemanticsActions.OnClick) != null })
        assertEquals("Existing", db.bookmarkDao().getBookmarkById("existing")?.title)
    }

    @Test
    fun duplicateAppearsAfterDialogOpens() = fixture { db ->
        openConfirmation("one", R.string.trash_restore)
        composeRule.waitUntil(5000) {
            composeRule.onAllNodes(hasText(context.getString(R.string.trash_restore_title)), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        db.bookmarkDao().insertOrUpdate(
            BookmarkEntity("existing", "Keep me", "https://example.com/one", "", createdAt = 1, updatedAt = 1)
        )
        val restoreLabel = context.getString(R.string.trash_restore)
        val disabledRestore = SemanticsMatcher("disabled restore") { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == restoreLabel } == true &&
                generateSequence(node) { it.parent }.any { it.config.getOrNull(SemanticsProperties.Disabled) != null }
        }
        composeRule.waitUntil(5000) {
            composeRule.onAllNodes(disabledRestore, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertNotNull(db.trashDao().getById("one"))
        assertEquals("Keep me", db.bookmarkDao().getBookmarkById("existing")?.title)
    }

    @Test
    fun writeFailureRetainsDialogAndCanRetry() = fixture { db ->
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_trash_delete BEFORE DELETE ON trashed_bookmarks BEGIN SELECT RAISE(ABORT, 'injected'); END"
        )
        openConfirmation("one", R.string.trash_restore)
        composeRule.onNodeWithTag("trash_confirm", useUnmergedTree = true).performClick()
        val failedLabel = context.getString(R.string.common_save_failed)
        composeRule.waitUntil(5000) {
            composeRule.onAllNodes(hasText(failedLabel), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertNotNull(db.trashDao().getById("one"))
        assertNull(db.bookmarkDao().getBookmarkById("one"))

        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_trash_delete")
        composeRule.onNodeWithTag("trash_confirm", useUnmergedTree = true).performClick()
        awaitTrashDeleted(db, "one")
        assertNotNull(db.bookmarkDao().getBookmarkById("one"))
    }

    @Test
    fun longRowsAndConfirmationRemainUsableAtLargeFontAcrossThemes() {
        val row = TrashBookmarkRow(
            TrashedBookmarkEntity(
                "long", "Long title ".repeat(30),
                "https://example.com/" + "long-path/".repeat(30), "", createdAt = 1, updatedAt = 1,
                note = "memo".repeat(50), isPrivate = false, trashedAt = 2
            ),
            false
        )
        val state = mutableStateOf(TrashUiState(loading = false, rows = listOf(row)))
        var confirmed = 0
        composeRule.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                Box(Modifier.width(320.dp)) {
                    TidiloTheme(theme = AppTheme.DEFAULT, darkTheme = false, dynamicColor = false, blurEnabled = false) {
                        Surface(color = MaterialTheme.colorScheme.background) {
                            TrashContent(
                                state.value, {},
                                { id, action -> state.value = state.value.copy(confirmation = TrashConfirmation(id, action)) },
                                { state.value = state.value.copy(confirmation = null) },
                                { confirmed++ },
                                {}
                            )
                        }
                    }
                }
            }
        }
        openConfirmation("long", R.string.trash_restore)
        val confirm = composeRule.onNodeWithTag("trash_confirm", useUnmergedTree = true).fetchSemanticsNode()
        assertNull(confirm.config.getOrNull(SemanticsProperties.Disabled))
        assertTrue(confirm.boundsInRoot.width > 0f)
        composeRule.onNodeWithTag("trash_confirm", useUnmergedTree = true).performClick()
        composeRule.waitForIdle()
        assertEquals(1, confirmed)
    }

    @Test
    fun shortDisplayKeepsConfirmationReachable() {
        val row = TrashBookmarkRow(
            TrashedBookmarkEntity(
                "short", "Long title ".repeat(20), "https://example.com/long-path", "",
                createdAt = 1, updatedAt = 1, note = "", isPrivate = false, trashedAt = 2
            ),
            false
        )
        val state = mutableStateOf(TrashUiState(loading = false, rows = listOf(row)))
        var confirmed = false
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                Box(Modifier.size(320.dp, 480.dp)) {
                    TidiloTheme(dynamicColor = false, blurEnabled = false) {
                        Surface(color = MaterialTheme.colorScheme.background) {
                            TrashContent(
                                state.value, {},
                                { id, action -> state.value = state.value.copy(confirmation = TrashConfirmation(id, action)) },
                                { state.value = state.value.copy(confirmation = null) },
                                { confirmed = true },
                                {}
                            )
                        }
                    }
                }
            }
        }
        openConfirmation("short", R.string.trash_restore)
        composeRule.onNodeWithTag("trash_confirm", useUnmergedTree = true).performClick()
        composeRule.waitForIdle()
        assertTrue(confirmed)
    }
}
