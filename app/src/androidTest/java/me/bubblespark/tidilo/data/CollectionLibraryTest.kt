package me.bubblespark.tidilo.data

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.config.AppConfig
import me.bubblespark.tidilo.data.db.*
import me.bubblespark.tidilo.ui.screens.library.*
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CollectionLibraryTest {
    @get:Rule val composeRule = createComposeRule()

    private fun absent(text: String): Boolean {
        composeRule.waitForIdle()
        return composeRule.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
    }

    @Test fun pagingUsesMembershipAndSurvivesRapidSwitchesAndDeletion(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val caches = OfflineVisualCaches()
        val library = InMemoryLibrary(context)
        val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val oldPrivateVisible = AppConfig.isPrivateContentVisible.value
        val db = library.db
        val ids = listOf("collection-a", "collection-b")
        try {
            library.awaitReady()
            db.withTransaction {
                ids.forEach { db.collectionDao().insert(CollectionEntity(it, "Collection $it", "Description", "folder", null, 0, 1, 1)) }
                db.bookmarkDao().insertAll(listOf(
                    BookmarkEntity("one", "Only alpha", "https://example.com/one", "", createdAt = 2, updatedAt = 2),
                    BookmarkEntity("two", "Only beta", "https://example.com/two", "", createdAt = 1, updatedAt = 1),
                    BookmarkEntity("hidden", "Hidden member", "https://example.com/hidden", "", createdAt = 3, updatedAt = 3, isPrivate = true)))
                db.collectionDao().insertMembership(BookmarkCollectionCrossRef("one", ids[0]))
                db.collectionDao().insertMembership(BookmarkCollectionCrossRef("two", ids[1]))
                db.collectionDao().insertMembership(BookmarkCollectionCrossRef("hidden", ids[0]))
            }
            lateinit var vm: LibraryViewModel
            composeRule.runOnUiThread {
                AppConfig.setPrivateContentVisible(false)
                // LibraryScreen also reads the app-wide flag, so the ViewModel shares it.
                vm = LibraryViewModel(library.store, SavedStateHandle(), AppConfig.isPrivateContentVisible, uiScope)
                vm.setFilterMode(LibraryFilterMode.COLLECTION)
                vm.selectCollectionFilter(ids[0])
            }
            composeRule.setContent { TidiloTheme(darkTheme = false, dynamicColor = false, blurEnabled = false) { LibraryScreen(vm) } }
            composeRule.awaitNode(hasText("Only alpha"))
            assertTrue(absent("Only beta"))
            for (mode in listOf(LibraryViewMode.GRID, LibraryViewMode.LIST)) {
                composeRule.runOnUiThread { vm.setViewMode(mode); vm.selectCollectionFilter(ids[1]) }
                composeRule.runOnUiThread { vm.selectCollectionFilter(ids[0]) }
                composeRule.awaitNode(hasText("Only alpha"))
            }
            composeRule.runOnUiThread { AppConfig.setPrivateContentVisible(true) }
            composeRule.awaitNode(hasText("Hidden member"))
            composeRule.runOnUiThread { AppConfig.setPrivateContentVisible(false) }
            composeRule.awaitNode(hasText("Only alpha"))
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodes(hasText("Hidden member"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
            }
            val selected = requireNotNull(db.collectionDao().getById(ids[0]))
            db.collectionDao().update(selected.copy(title = "Renamed", colorIndex = 3))
            composeRule.awaitNode(hasText("Renamed"))
            db.collectionDao().delete(ids[0])
            // The screen clears a deleted selection, so wait through the rule that drives its frames.
            composeRule.waitUntil(5_000) { vm.selectedCollectionId.value == null }
            composeRule.awaitNode(hasText("Collection ${ids[1]}"))
        } finally {
            composeRule.runOnUiThread { AppConfig.setPrivateContentVisible(oldPrivateVisible) }
            uiScope.cancel()
            library.close()
            caches.close()
        }
    }
}
