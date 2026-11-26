package me.bubblespark.tidilo.data

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.ViewModelProvider
import androidx.room.withTransaction
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.bubblespark.tidilo.MainActivity
import me.bubblespark.tidilo.config.AppConfig
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.ui.screens.library.LibraryFilterMode
import me.bubblespark.tidilo.ui.screens.library.LibraryViewModel
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** Full-app state restoration across one activity recreation per test, on the app database. */
class CollectionRecreationTest {
    @get:Rule val composeRule = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = AppDatabase.getInstance(context)

    /** An editable field showing [text] inside the collection editor sheet, not the screen behind it. */
    private fun editable(text: String) = SemanticsMatcher("editable \"$text\"") {
        it.config.getOrNull(SemanticsProperties.EditableText)?.text == text &&
            it.config.getOrNull(SemanticsActions.SetText) != null
    } and inWindowShowing("New Collection")

    private fun typeInto(text: String, value: String) {
        val field = composeRule.awaitNode(editable(text))
        composeRule.runOnUiThread { check(field.config[SemanticsActions.SetText].action?.invoke(AnnotatedString(value)) == true) }
        composeRule.awaitNode(editable(value))
    }

    private fun dismissSheet() {
        val sheet = composeRule.awaitNode(SemanticsMatcher("dismissable") { it.config.getOrNull(SemanticsActions.Dismiss) != null })
        composeRule.runOnUiThread { check(sheet.config[SemanticsActions.Dismiss].action?.invoke() == true) }
        composeRule.waitForIdle()
    }

    private fun absent(text: String) = composeRule.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()

    /** Launches the app with overlay blur off and offline caches, restoring the Library filter afterwards. */
    private fun withApp(block: suspend (ActivityScenario<MainActivity>) -> Unit) = runBlocking {
        val oldBlur = AppConfig.overlayBlurEnabled.value
        val caches = OfflineVisualCaches()
        var scenario: ActivityScenario<MainActivity>? = null
        var oldMode: LibraryFilterMode? = null
        try {
            AppConfig.setOverlayBlurEnabled(false)
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity { oldMode = ViewModelProvider(it)[LibraryViewModel::class.java].filterMode.value }
            block(scenario)
        } finally {
            oldMode?.let { mode -> scenario?.onActivity { ViewModelProvider(it)[LibraryViewModel::class.java].setFilterMode(mode) } }
            scenario?.close()
            caches.close()
            AppConfig.setOverlayBlurEnabled(oldBlur)
        }
    }

    @Test fun actualLibraryEditorRestoresAfterActivityRecreation() = withApp { scenario ->
        val draft = "Restored ${UUID.randomUUID().toString().take(8)}"
        scenario.onActivity {
            val model = ViewModelProvider(it)[LibraryViewModel::class.java]
            model.setFilterMode(LibraryFilterMode.COLLECTION)
            model.selectCollectionFilter(null)
        }
        composeRule.clickNode(composeRule.awaitNode(hasContentDescription("New Collection", substring = true)))
        typeInto("", draft)
        scenario.recreate()
        composeRule.awaitNode(editable(draft))
        dismissSheet()
        assertTrue(db.collectionDao().observeCollections(true).first().none { it.collection.title == draft })
    }

    @Test fun detailSequentialCreateSurvivesActivityRecreation() = withApp { scenario ->
        val bookmarkId = UUID.randomUUID().toString()
        val bookmarkTitle = "RecreateDetail ${bookmarkId.take(6)}"
        val draftTitle = "RecreatedCol ${bookmarkId.take(6)}"
        val now = System.currentTimeMillis() + 1_000_000L
        try {
            db.bookmarkDao().insertOrUpdate(
                BookmarkEntity(bookmarkId, bookmarkTitle, "https://example.com/$bookmarkId", "", createdAt = now, updatedAt = now))
            scenario.onActivity { ViewModelProvider(it)[LibraryViewModel::class.java].setFilterMode(LibraryFilterMode.NEWEST_FIRST) }
            composeRule.clickNode(composeRule.awaitNode(hasText(bookmarkTitle)))
            composeRule.clickNode(composeRule.awaitNode(hasContentDescription("Choose collections", substring = true)))
            composeRule.awaitNode(hasText("Choose collections"))
            composeRule.clickNode(composeRule.awaitNode(enabledButton("New collection")))
            composeRule.awaitNode(hasText("New Collection"))
            typeInto("", draftTitle)

            scenario.recreate()
            composeRule.awaitNode(hasText("New Collection"))
            composeRule.awaitNode(editable(draftTitle))
            assertTrue("Only creator sheet should be visible after recreation", absent("Choose collections"))
            composeRule.clickNode(composeRule.awaitNode(hasText("Create")))
            composeRule.awaitNode(hasText("Choose collections"))
            composeRule.awaitNode(SemanticsMatcher("checked \"$draftTitle\" row") { node ->
                node.config.getOrNull(SemanticsProperties.Role) == Role.Checkbox &&
                    node.config.getOrNull(SemanticsProperties.ToggleableState) == ToggleableState.On &&
                    node.children.any { child -> child.config.getOrNull(SemanticsProperties.Text)?.any { it.text == draftTitle } == true }
            })
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                val created = db.collectionDao().observeCollections(true).first()
                    .filter { it.collection.title == draftTitle }.map { it.collection.id }
                db.withTransaction {
                    created.forEach { db.collectionDao().delete(it) }
                    db.bookmarkDao().deleteById(bookmarkId)
                }
            }
        }
    }

    private fun enabledButton(label: String) = SemanticsMatcher("enabled button \"$label\"") { node ->
        node.config.getOrNull(SemanticsProperties.Role) == Role.Button &&
            !node.config.contains(SemanticsProperties.Disabled) &&
            generateSequence(listOf(node)) { level -> level.flatMap { it.children }.takeIf { it.isNotEmpty() } }
                .flatten().any { n -> n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true }
    }
}
