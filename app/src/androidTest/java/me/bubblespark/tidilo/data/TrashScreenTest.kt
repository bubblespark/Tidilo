package me.bubblespark.tidilo.data

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.*
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.room.Room
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.config.AppTheme
import me.bubblespark.tidilo.data.db.*
import me.bubblespark.tidilo.ui.screens.trash.*
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import me.bubblespark.tidilo.ui.screens.library.TrashBookmarkDetailContent
import me.bubblespark.tidilo.ui.screens.library.BookmarkItem
import me.bubblespark.tidilo.ui.screens.library.Tag
import org.junit.Assert.*
import org.junit.Test

internal fun clickTrashNode(host: HeaderTestHost, node: SemanticsNode) = host.main {
    var target = node
    while (target.config.getOrNull(SemanticsActions.OnClick) == null) target = requireNotNull(target.parent)
    check(target.config[SemanticsActions.OnClick].action?.invoke() == true)
}

class TrashScreenTest {
    private suspend fun fixture(block: suspend (HeaderTestHost, ComponentActivity, AppDatabase, () -> Int) -> Unit) {
        val host = HeaderTestHost()
        val db = Room.inMemoryDatabaseBuilder(host.context, AppDatabase::class.java).build()
        var screen: ComponentActivity? = null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var opens = 0
        try {
            for (id in listOf("one", "two")) {
                db.bookmarkDao().insertOrUpdate(BookmarkEntity(id, "Deleted $id", "https://example.com/$id", "Summary", createdAt = 1, updatedAt = 1))
                db.moveBookmarkToTrash(id, if (id == "one") 3 else 2, 100)
            }
            val store = object : TrashStore {
                override fun observe(showNsfw: Boolean) = db.trashDao().observeTrash(showNsfw)
                override suspend fun restore(id: String) = db.restoreTrashedBookmark(id)
                override suspend fun delete(id: String) = db.deleteTrashedBookmark(id)
                override fun retry() = Unit
            }
            screen = host.launch()
            val activity = screen
            host.main {
                val model = TrashViewModel(store, SavedStateHandle(), MutableStateFlow(true), scope)
                activity.setContent { TidiloTheme {
                    val state by model.uiState.collectAsStateWithLifecycle()
                    Surface(color = MaterialTheme.colorScheme.background) {
                        TrashContent(state, {}, { opens++ }, model::requestAction, model::dismissConfirmation,
                            model::confirmAction, model::retry)
                    }
                } }
            }
            host.await(activity) { host.text(it, "Deleted one") }
            block(host, activity, db) { opens }
        } finally {
            screen?.let { host.main { it.finish(); scope.cancel() } }
            db.close()
        }
    }

    private suspend fun action(host: HeaderTestHost, screen: ComponentActivity, id: String, resource: Int) {
        withTimeout(5000) {
            var confirming = true
            while (confirming) {
                host.main { confirming = host.nodes(screen).any { it.config.getOrNull(SemanticsProperties.TestTag) == "trash_confirm" } }
                if (confirming) delay(20)
            }
        }
        clickTrashNode(host, host.await(screen) { it.config.getOrNull(SemanticsProperties.TestTag) == "trash_actions:$id" })
        clickTrashNode(host, host.await(screen) { host.text(it, host.context.getString(resource)) })
        host.await(screen) { it.config.getOrNull(SemanticsProperties.TestTag) == "trash_confirm" }
    }

    @Test fun bothActionsRequireConfirmation(): Unit = runBlocking { fixture { host, screen, db, _ ->
        action(host, screen, "one", R.string.trash_restore)
        host.await(screen) { host.text(it, host.context.getString(R.string.trash_restore_title)) }
        assertNotNull(db.trashDao().getById("one")); assertNull(db.bookmarkDao().getBookmarkById("one"))
        clickTrashNode(host, host.await(screen) { host.text(it, host.context.getString(R.string.common_cancel)) })
        assertNotNull(db.trashDao().getById("one"))
        action(host, screen, "one", R.string.trash_restore)
        clickTrashNode(host, host.await(screen) { it.config.getOrNull(SemanticsProperties.TestTag) == "trash_confirm" })
        withTimeout(5000) { while (db.trashDao().getById("one") != null) delay(20) }
        assertNotNull(db.bookmarkDao().getBookmarkById("one"))
        action(host, screen, "two", R.string.common_delete)
        host.await(screen) { host.text(it, host.context.getString(R.string.trash_delete_title)) }
        clickTrashNode(host, host.await(screen) { host.text(it, host.context.getString(R.string.common_cancel)) })
        assertNotNull(db.trashDao().getById("two"))
        action(host, screen, "two", R.string.common_delete)
        clickTrashNode(host, host.await(screen) { it.config.getOrNull(SemanticsProperties.TestTag) == "trash_confirm" })
        withTimeout(5000) { while (db.trashDao().getById("two") != null) delay(20) }
        assertNull(db.bookmarkDao().getBookmarkById("two"))
        host.await(screen) { host.text(it, host.context.getString(R.string.trash_empty)) }
    } }

    @Test fun duplicatesHaveLabelAndDisabledRestore(): Unit = runBlocking { fixture { host, screen, db, opens ->
        db.bookmarkDao().insertOrUpdate(BookmarkEntity("existing", "Existing", "https://example.com/one", "", createdAt = 1, updatedAt = 1, isNsfw = true))
        host.await(screen) { host.text(it, host.context.getString(R.string.trash_duplicate)) }
        clickTrashNode(host, host.await(screen) { it.config.getOrNull(SemanticsProperties.TestTag) == "trash_actions:one" })
        val restore = host.await(screen) { host.text(it, host.context.getString(R.string.trash_restore)) }
        host.main {
            assertTrue(generateSequence(restore) { it.parent }.any { it.config.getOrNull(SemanticsProperties.Disabled) != null })
        }
        assertEquals(0, opens())
        clickTrashNode(host, host.await(screen) { host.text(it, host.context.getString(R.string.common_delete)) })
        clickTrashNode(host, host.await(screen) { host.text(it, host.context.getString(R.string.common_cancel)) })
        clickTrashNode(host, host.await(screen) { host.text(it, "Deleted one") })
        assertEquals(1, opens())
        assertEquals("Existing", db.bookmarkDao().getBookmarkById("existing")?.title)
    } }

    @Test fun duplicateAppearsAfterDialogOpens(): Unit = runBlocking { fixture { host, screen, db, _ ->
        action(host, screen, "one", R.string.trash_restore)
        host.await(screen) { host.text(it, host.context.getString(R.string.trash_restore_title)) }
        db.bookmarkDao().insertOrUpdate(BookmarkEntity("existing", "Keep me", "https://example.com/one", "", createdAt = 1, updatedAt = 1))
        val confirm = host.await(screen) {
            host.text(it, host.context.getString(R.string.trash_restore)) && generateSequence(it) { node -> node.parent }.any { node -> node.config.getOrNull(SemanticsProperties.Disabled) != null }
        }
        assertNotNull(confirm)
        assertNotNull(db.trashDao().getById("one"))
        assertEquals("Keep me", db.bookmarkDao().getBookmarkById("existing")?.title)
    } }

    @Test fun writeFailureRetainsDialogAndCanRetry(): Unit = runBlocking { fixture { host, screen, db, _ ->
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_trash_delete BEFORE DELETE ON trashed_bookmarks BEGIN SELECT RAISE(ABORT, 'injected'); END")
        action(host, screen, "one", R.string.trash_restore)
        clickTrashNode(host, host.await(screen) { it.config.getOrNull(SemanticsProperties.TestTag) == "trash_confirm" })
        host.await(screen) { host.text(it, host.context.getString(R.string.common_save_failed)) }
        assertNotNull(db.trashDao().getById("one")); assertNull(db.bookmarkDao().getBookmarkById("one"))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_trash_delete")
        clickTrashNode(host, host.await(screen) { it.config.getOrNull(SemanticsProperties.TestTag) == "trash_confirm" })
        withTimeout(5000) { while (db.trashDao().getById("one") != null) delay(20) }
        assertNotNull(db.bookmarkDao().getBookmarkById("one"))
    } }

    @Test fun longRowsAndConfirmationRemainUsableAtLargeFontAcrossThemes(): Unit = runBlocking {
        val host = HeaderTestHost()
        val screen = host.launch()
        val appearance = mutableStateOf(AppTheme.DEFAULT to false)
        val row = TrashBookmarkRow(TrashedBookmarkEntity("long", "Long title ".repeat(30),
            "https://example.com/" + "long-path/".repeat(30), "", createdAt = 1, updatedAt = 1,
            note = "memo".repeat(50), isNsfw = false, trashedAt = 2), false)
        val state = mutableStateOf(TrashUiState(loading = false, rows = listOf(row)))
        var confirmed = 0
        try {
            host.main { screen.setContent {
                val density = LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                    Box(Modifier.width(320.dp)) {
                        TidiloTheme(theme = appearance.value.first, darkTheme = appearance.value.second, dynamicColor = false) {
                            Surface(color = MaterialTheme.colorScheme.background) {
                                TrashContent(state.value, {}, {}, { id, action -> state.value = state.value.copy(confirmation = TrashConfirmation(id, action)) },
                                    { state.value = state.value.copy(confirmation = null) }, { confirmed++ }, {})
                            }
                        }
                    }
                }
            } }
            for (theme in listOf(AppTheme.DEFAULT, AppTheme.MODERN)) for (dark in listOf(false, true)) {
                host.main { appearance.value = theme to dark; state.value = state.value.copy(confirmation = null) }
                action(host, screen, "long", R.string.trash_restore)
                val confirm = host.await(screen) { it.config.getOrNull(SemanticsProperties.TestTag) == "trash_confirm" }
                host.main {
                    assertNull(confirm.config.getOrNull(SemanticsProperties.Disabled))
                    assertTrue(confirm.boundsInRoot.width > 0f)
                    assertTrue(confirm.boundsInRoot.bottom <= screen.window.decorView.height)
                }
                host.capture("trash-${theme.name.lowercase()}-$dark")
                clickTrashNode(host, confirm)
            }
            assertEquals(4, confirmed)
        } finally { host.main { screen.finish() } }
    }

    @Test fun shortDisplayKeepsConfirmationAndDetailBrowserReachable(): Unit = runBlocking {
        val host = HeaderTestHost()
        fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            host.instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
        val previous = shell("wm size").lineSequence().firstOrNull { it.startsWith("Override size:") }
            ?.substringAfter(':')?.trim() ?: "reset"
        val density = host.context.resources.displayMetrics.density
        var screen: ComponentActivity? = null
        try {
            shell("wm size ${(320 * density).toInt()}x${(480 * density).toInt()}")
            val activity = host.launch()
            screen = activity
            val row = TrashBookmarkRow(TrashedBookmarkEntity("short", "Long title ".repeat(20), "https://example.com/long-path", "",
                createdAt = 1, updatedAt = 1, note = "", isNsfw = false, trashedAt = 2), false)
            val state = mutableStateOf(TrashUiState(loading = false, rows = listOf(row)))
            var confirmed = false
            host.main { activity.setContent {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                    TidiloTheme(dynamicColor = false) { Surface(color = MaterialTheme.colorScheme.background) {
                        TrashContent(state.value, {}, {}, { id, action -> state.value = state.value.copy(confirmation = TrashConfirmation(id, action)) },
                            { state.value = state.value.copy(confirmation = null) }, { confirmed = true }, {})
                    } }
                }
            } }
            action(host, activity, "short", R.string.trash_restore)
            host.capture("trash-short-confirmation")
            clickTrashNode(host, host.await(activity) { it.config.getOrNull(SemanticsProperties.TestTag) == "trash_confirm" })
            assertTrue(confirmed)
            val item = BookmarkItem("short", "Long title ".repeat(20), "https://example.com/long-path", "Long summary ".repeat(20),
                Tag("Article", "Tech", listOf("Kotlin", "Android")), note = "Memo ".repeat(40))
            host.main { activity.setContent {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                    TidiloTheme(dynamicColor = false) { Surface(color = MaterialTheme.colorScheme.background) {
                        TrashBookmarkDetailContent(item, {})
                    } }
                }
            } }
            val browser = host.await(activity) { host.description(it, host.context.getString(R.string.bookmark_open_in_browser)) }
            val monitor = host.instrumentation.addMonitor(android.content.IntentFilter(android.content.Intent.ACTION_VIEW).apply {
                addDataScheme("https"); addDataAuthority("example.com", null)
            }, android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_OK, null), true)
            try {
                host.capture("trash-short-detail")
                clickTrashNode(host, browser)
                assertTrue(host.instrumentation.checkMonitorHit(monitor, 1))
            } finally { host.instrumentation.removeMonitor(monitor) }
        } finally {
            screen?.let { host.main { it.finish() } }
            shell("wm size $previous")
        }
    }
}
