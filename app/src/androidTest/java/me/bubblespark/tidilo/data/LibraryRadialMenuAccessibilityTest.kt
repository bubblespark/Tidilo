package me.bubblespark.tidilo.data

import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.*
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.ui.screens.library.LibraryFilterMode
import me.bubblespark.tidilo.ui.screens.library.LibraryScreen
import me.bubblespark.tidilo.ui.screens.library.LibraryViewModel
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class LibraryRadialMenuAccessibilityTest {
    @Test fun closeRestoresTriggerFocusFromAnItem() = runBlocking {
        RadialMenuFixture().use { fixture ->
            for (physical in listOf(false, true)) {
                var stage = "open"
                try {
                    fixture.click("trigger")
                    fixture.node("search")
                    fixture.host.instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_TAB)
                    stage = "focus item"
                    fixture.host.await(fixture.activity) { it.config.getOrNull(SemanticsProperties.TestTag) == "library_radial_search" &&
                        it.config.getOrNull(SemanticsProperties.Focused) == true }
                    stage = "close"
                    if (physical) {
                        val center = fixture.center("trigger")
                        val time = android.os.SystemClock.uptimeMillis()
                        fixture.send(android.view.MotionEvent.ACTION_DOWN, center, time)
                        try { fixture.send(android.view.MotionEvent.ACTION_UP, center, time) }
                        finally { fixture.send(android.view.MotionEvent.ACTION_CANCEL, center, time) }
                    } else fixture.click("trigger")
                    stage = "restore trigger"
                    fixture.host.await(fixture.activity) { it.config.getOrNull(SemanticsProperties.TestTag) == "library_radial_trigger" &&
                        it.config.getOrNull(SemanticsProperties.Focused) == true }
                    fixture.host.main { assertFalse(fixture.state.isOpen); assertTrue(fixture.actions.isEmpty()) }
                } catch (error: Exception) { throw AssertionError("physical=$physical, stage=$stage", error) }
            }
        }
    }
    @Test fun everyShortcutHasIndependentActivationAndOnlyFiltersExposeSelection() = runBlocking {
        RadialMenuFixture().use { fixture ->
            for ((suffix, action) in listOf("search" to "SEARCH", "collection" to "COLLECTION", "rss" to "RSS",
                "domain" to "DOMAIN", "newest_first" to "NEWEST_FIRST")) {
                fixture.click("trigger")
                fixture.node(suffix)
                fixture.host.main {
                    val buttons = RadialMenuFixture.effectiveNodes(fixture.activity).filter {
                        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("library_radial_") == true &&
                            it.config.getOrNull(SemanticsActions.OnClick) != null
                    }
                    assertEquals(6, buttons.size)
                    assertEquals(4, buttons.count { it.config.contains(SemanticsProperties.Selected) })
                    assertEquals(1, buttons.count { it.config.getOrNull(SemanticsProperties.Selected) == true })
                }
                fixture.click(suffix)
                fixture.host.main { assertEquals(action, fixture.actions.last()); assertFalse(fixture.state.isOpen) }
            }
            fixture.click("trigger")
            fixture.host.instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_TAB)
            fixture.host.instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_ESCAPE)
            delay(100)
            fixture.host.main { assertFalse(fixture.state.isOpen) }
            fixture.host.instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
            fixture.node("search")
            fixture.host.instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_SPACE)
            delay(100)
            fixture.host.main { assertFalse(fixture.state.isOpen) }
        }
    }

    @Test fun realLibraryEffectiveTreeHidesAndRestoresBackgroundActions() = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        val dao = AppDatabase.getInstance(host.context).bookmarkDao()
        val id = "radial-${UUID.randomUUID()}"
        lateinit var model: LibraryViewModel
        var modelReady = false
        var previous = LibraryFilterMode.NEWEST_FIRST
        try {
            dao.insertOrUpdate(BookmarkEntity(id, "Radial accessibility fixture", "https://radial.invalid/$id",
                "Summary", createdAt = System.currentTimeMillis() + 100000, updatedAt = 1))
            host.main {
                model = ViewModelProvider(activity)[LibraryViewModel::class.java]
                modelReady = true
                previous = model.filterMode.value
                model.setFilterMode(LibraryFilterMode.NEWEST_FIRST)
                activity.setContent { MaterialTheme { LibraryScreen(viewModel = model) } }
            }
            val title = host.context.getString(R.string.library_header_title)
            host.await(activity) { host.text(it, "Radial accessibility fixture") }
            fun backgroundActions(): Int = RadialMenuFixture.effectiveNodes(activity).count { node ->
                node.config.getOrNull(SemanticsActions.OnClick) != null &&
                    (host.text(node, title) || node.config.getOrNull(SemanticsProperties.Text)?.any {
                        it.text.contains("Radial accessibility fixture") } == true)
            }
            var baseline = 0
            host.main { baseline = backgroundActions(); assertTrue(baseline > 0) }
            val trigger = host.await(activity) { it.config.getOrNull(SemanticsProperties.TestTag) == "library_radial_trigger" }
            host.main { trigger.config[SemanticsActions.OnClick].action!!.invoke() }
            host.await(activity) { host.description(it, host.context.getString(R.string.common_close)) }
            host.main { assertEquals(0, backgroundActions()) }
            for (key in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)) repeat(5) {
                host.instrumentation.sendKeyDownUpSync(key)
                delay(30)
                host.main {
                    assertTrue("Directional focus escaped the menu: $key", host.nodes(activity).any {
                        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("library_radial_") == true &&
                            it.config.getOrNull(SemanticsProperties.Focused) == true
                    })
                }
            }
            val close = host.await(activity) { it.config.getOrNull(SemanticsProperties.TestTag) == "library_radial_trigger" }
            host.main { assertTrue(close.config[SemanticsActions.RequestFocus].action!!.invoke()) }
            host.instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_TAB)
            host.await(activity) { it.config.getOrNull(SemanticsProperties.TestTag) == "library_radial_search" &&
                it.config.getOrNull(SemanticsProperties.Focused) == true }
            host.instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            host.await(activity) { host.description(it, host.context.getString(R.string.library_shortcuts_open)) }
            host.await(activity) { it.config.getOrNull(SemanticsProperties.TestTag) == "library_radial_trigger" &&
                it.config.getOrNull(SemanticsProperties.Focused) == true }
            host.main { assertEquals(baseline, backgroundActions()) }
        } finally {
            host.main { if (modelReady) model.setFilterMode(previous); activity.setContent {}; activity.finish() }
            dao.deleteById(id)
        }
    }
}
