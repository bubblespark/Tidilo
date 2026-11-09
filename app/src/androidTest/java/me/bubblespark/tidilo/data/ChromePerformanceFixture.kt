package me.bubblespark.tidilo.data

import android.content.Context
import android.util.AtomicFile
import android.os.Process
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.ui.screens.library.*
import java.util.UUID
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

internal class ChromePerformanceFixture(private val host: HeaderTestHost) : AutoCloseable {
    private val db = AppDatabase.getInstance(host.context)
    private val prefix = "chrome-${UUID.randomUUID()}"
    val domain = "chrome-frame-fixture.invalid"
    val detailId = "$prefix-0"
    private val ids = (0 until 400).map { "$prefix-$it" }
    private val prefs = host.context.getSharedPreferences("tidilo_library_prefs", Context.MODE_PRIVATE)
    private val keys = listOf("library_filter_mode", "library_view_mode")
    private var saved: Map<String, String?>? = null
    private val journal = ChromeFixtureJournal(host.context)
    private var ownsJournal = false
    private val store = ViewModelStore()
    private val oldDiscoverer = DomainIconManager.htmlDiscoverer
    private val oldDownloader = DomainIconManager.httpDownloader
    private val oldImageDownloader = OgImageManager.imageDownloader
    lateinit var vm: LibraryViewModel
        private set

    fun prepare() = runBlocking {
        journal.recover()
        require(domain !in db.bookmarkDao().distinctDomains()) { "Performance fixture domain already populated" }
        saved = keys.associateWith { prefs.getString(it, null) }
        journal.save(prefix, ids, requireNotNull(saved))
        ownsJournal = true
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
            try {
                if (ownsJournal) {
                    try { ids.forEach { db.bookmarkDao().deleteById(it) } }
                    finally { journal.restorePreferences(requireNotNull(saved)) }
                    journal.clear()
                }
            }
            finally {
                DomainIconManager.htmlDiscoverer = oldDiscoverer
                DomainIconManager.httpDownloader = oldDownloader
                OgImageManager.imageDownloader = oldImageDownloader
            }
        }
    }
}

/** Persist ownership before insertion so a killed instrumentation process can be recovered safely. */
internal class ChromeFixtureJournal(private val context: Context) {
    private val file = AtomicFile(File(context.cacheDir, "chrome-performance-owner.json"))
    private val keys = listOf("library_filter_mode", "library_view_mode")

    fun save(prefix: String, ids: List<String>, preferences: Map<String, String?>, pid: Int = Process.myPid()) {
        val prefs = JSONObject()
        keys.forEach { prefs.put(it, preferences[it] ?: JSONObject.NULL) }
        val value = JSONObject().put("prefix", prefix).put("ids", JSONArray(ids)).put("preferences", prefs).put("pid", pid)
        val stream = file.startWrite()
        try { stream.write(value.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (failure: Throwable) { file.failWrite(stream); throw failure }
    }

    suspend fun recover() {
        if (!file.baseFile.exists()) return
        val value = JSONObject(file.readFully().toString(Charsets.UTF_8))
        require(value.getInt("pid") != Process.myPid()) { "Performance fixture already active" }
        val prefix = value.getString("prefix")
        require(Regex("chrome-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(prefix))
        val array = value.getJSONArray("ids")
        val ids = (0 until array.length()).map { array.getString(it) }
        require(ids == (0 until 400).map { "$prefix-$it" }) { "Invalid fixture ownership list" }
        val dao = AppDatabase.getInstance(context).bookmarkDao()
        // Validate every present record before deleting any record.
        for ((index, id) in ids.withIndex()) dao.getBookmarkById(id)?.let { row ->
            require(row.domain == "chrome-frame-fixture.invalid" && row.url == "https://chrome-frame-fixture.invalid/$index" && row.title == "Chrome fixture article $index")
            val description = if (index == 0) (1..100).joinToString("\n\n") { "Paragraph $it. Deterministic long content for scrolling and rendering measurements." }
                else "Deterministic local summary $index"
            require(row.description == description && row.note.isEmpty() && !row.isLiked && row.category == null && row.subcategory == null &&
                row.entities.isEmpty() && row.rssFeedId == null && row.ogImageUrl == null && row.createdAt == 1000L + index && row.updatedAt == 1L)
        }
        ids.forEach { dao.deleteById(it) }
        val prefs = value.getJSONObject("preferences")
        restorePreferences(keys.associateWith { if (prefs.isNull(it)) null else prefs.getString(it) })
        clear()
    }

    fun restorePreferences(values: Map<String, String?>) {
        val editor = context.getSharedPreferences("tidilo_library_prefs", Context.MODE_PRIVATE).edit()
        keys.forEach { key -> values[key]?.let { editor.putString(key, it) } ?: editor.remove(key) }
        check(editor.commit())
    }

    fun clear() { file.delete() }
}
