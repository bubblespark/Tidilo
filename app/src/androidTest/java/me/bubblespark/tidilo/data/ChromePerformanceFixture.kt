package me.bubblespark.tidilo.data

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.ui.screens.library.*
import java.util.UUID

internal class ChromePerformanceFixture(private val host: HeaderTestHost) : AutoCloseable {
    private val db = AppDatabase.getInstance(host.context)
    private val prefix = "chrome-${UUID.randomUUID()}"
    val domain = "chrome-frame-fixture.invalid"
    val detailId = "$prefix-0"
    private val ids = (0 until 400).map { "$prefix-$it" }
    private val prefs = host.context.getSharedPreferences("tidilo_library_prefs", Context.MODE_PRIVATE)
    private val keys = listOf("library_filter_mode", "library_view_mode")
    private val saved = keys.associateWith { prefs.getString(it, null) }
    private val store = ViewModelStore()
    private val oldDiscoverer = DomainIconManager.htmlDiscoverer
    private val oldDownloader = DomainIconManager.httpDownloader
    private val oldImageDownloader = OgImageManager.imageDownloader
    lateinit var vm: LibraryViewModel
        private set

    fun prepare() = runBlocking {
        require(domain !in db.bookmarkDao().distinctDomains()) { "Performance fixture domain already populated" }
        DomainIconManager.htmlDiscoverer = { DomainIconManager.HtmlDiscoveryResult(emptyList(), true, false) }
        DomainIconManager.httpDownloader = { DomainIconManager.DownloadResult(null, true, false) }
        OgImageManager.imageDownloader = { null }
        db.bookmarkDao().insertAll(ids.mapIndexed { index, id ->
            BookmarkEntity(id, "Chrome fixture article $index", "https://$domain/$index",
                if (index == 0) (1..100).joinToString("\n\n") { "Paragraph $it. Deterministic long content for scrolling and rendering measurements." }
                else "Deterministic local summary $index", createdAt = 1000L + index, updatedAt = 1)
        })
        host.main {
            vm = ViewModelProvider(store, ViewModelProvider.NewInstanceFactory())[LibraryViewModel::class.java]
            vm.setFilterMode(LibraryFilterMode.DOMAIN)
            vm.selectDomainFilter(domain)
            vm.setViewMode(LibraryViewMode.LIST)
        }
    }

    override fun close() = runBlocking {
        try {
            if (::vm.isInitialized) {
                val job = vm.viewModelScope.coroutineContext[Job]
                host.main { store.clear() }
                job?.join()
            }
        } finally {
            try { ids.forEach { db.bookmarkDao().deleteById(it) } }
            finally {
                val editor = prefs.edit()
                saved.forEach { (key, value) -> if (value == null) editor.remove(key) else editor.putString(key, value) }
                check(editor.commit())
                DomainIconManager.htmlDiscoverer = oldDiscoverer
                DomainIconManager.httpDownloader = oldDownloader
                OgImageManager.imageDownloader = oldImageDownloader
            }
        }
    }
}
