package me.bubblespark.tidilo.data

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.config.AppConfig
import me.bubblespark.tidilo.data.db.*
import me.bubblespark.tidilo.ui.screens.library.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class CollectionLibraryTest {
    @Test fun pagingUsesMembershipAndSurvivesRapidSwitchesAndDeletion() = runBlocking {
        val host = HeaderTestHost()
        val db = AppDatabase.getInstance(host.context)
        val id = UUID.randomUUID().toString()
        val ids = listOf("$id-a", "$id-b")
        val store = ViewModelStore()
        val activity = host.launch()
        lateinit var vm: LibraryViewModel
        var oldMode: LibraryFilterMode? = null
        var oldView: LibraryViewMode? = null
        val oldNsfw = AppConfig.isNsfwContentVisible.value
        try {
            ids.forEach { db.collectionDao().insert(CollectionEntity(it, "Collection $it", "Description", "folder", null, 0, 1, 1)) }
            db.bookmarkDao().insertAll(listOf(
                BookmarkEntity("$id-one", "Only alpha $id", "https://example.com/$id/one", "", createdAt = 2, updatedAt = 2),
                BookmarkEntity("$id-two", "Only beta $id", "https://example.com/$id/two", "", createdAt = 1, updatedAt = 1),
                BookmarkEntity("$id-hidden", "Hidden member $id", "https://example.com/$id/hidden", "", createdAt = 3, updatedAt = 3, isNsfw = true)))
            db.collectionDao().insertMembership(BookmarkCollectionCrossRef("$id-one", ids[0]))
            db.collectionDao().insertMembership(BookmarkCollectionCrossRef("$id-two", ids[1]))
            db.collectionDao().insertMembership(BookmarkCollectionCrossRef("$id-hidden", ids[0]))
            host.main {
                AppConfig.setNsfwContentVisible(false)
                vm = ViewModelProvider(store, ViewModelProvider.NewInstanceFactory())[LibraryViewModel::class.java]
                oldMode = vm.filterMode.value
                oldView = vm.viewMode.value
                vm.setFilterMode(LibraryFilterMode.COLLECTION)
                vm.selectCollectionFilter(ids[0])
                activity.setContent { MaterialTheme { LibraryScreen(vm) } }
            }
            host.await(activity) { host.text(it, "Only alpha $id") }
            delay(400)
            var containsBeta = false
            host.main { containsBeta = host.nodes(activity).any { host.text(it, "Only beta $id") } }
            assertFalse(containsBeta)
            for (mode in listOf(LibraryViewMode.GRID, LibraryViewMode.LIST)) {
                host.main { vm.setViewMode(mode); vm.selectCollectionFilter(ids[1]) }
                delay(60)
                host.main { vm.selectCollectionFilter(ids[0]) }
                host.await(activity) { host.text(it, "Only alpha $id") }
            }
            host.main { AppConfig.setNsfwContentVisible(true) }
            host.await(activity) { host.text(it, "Hidden member $id") }
            host.main { AppConfig.setNsfwContentVisible(false) }
            host.await(activity) { host.text(it, "Only alpha $id") }
            delay(400)
            var hiddenVisible = false
            host.main { hiddenVisible = host.nodes(activity).any { host.text(it, "Hidden member $id") } }
            assertFalse(hiddenVisible)
            val selected = requireNotNull(db.collectionDao().getById(ids[0]))
            db.collectionDao().update(selected.copy(title = "Renamed $id", colorIndex = 3))
            host.await(activity) { host.text(it, "Renamed $id") }
            db.collectionDao().delete(ids[0])
            withTimeout(5000) { while (vm.selectedCollectionId.value != null) delay(20) }
            host.await(activity) { host.text(it, "Collection ${ids[1]}") }
            assertNull(vm.selectedCollectionId.value)
        } finally {
            host.main { oldMode?.let(vm::setFilterMode); oldView?.let(vm::setViewMode); AppConfig.setNsfwContentVisible(oldNsfw); store.clear(); activity.finish() }
            ids.forEach { db.collectionDao().delete(it) }
            db.bookmarkDao().deleteById("$id-one"); db.bookmarkDao().deleteById("$id-two")
            db.bookmarkDao().deleteById("$id-hidden")
        }
    }
}
