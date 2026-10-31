package me.bubblespark.tidilo.data

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.width
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.ui.screens.library.AddEntityDialog
import me.bubblespark.tidilo.ui.screens.library.EntityLibraryState
import me.bubblespark.tidilo.ui.screens.library.EntityTagConflictDialog
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.*
import org.junit.Test

class EntityTagConflictLayoutTest {
    private val host = HeaderTestHost()
    private fun find(text: String): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isVisibleToUser && node.text?.toString() == text) return node
            repeat(node.childCount) { node.getChild(it)?.let { child -> visit(child)?.let { return it } } }
            return null
        }
        host.instrumentation.uiAutomation.clearCache()
        return host.instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
    }
    private suspend fun button(text: String): AccessibilityNodeInfo = withTimeout(8000) {
        var found = find(text)
        while (found == null) { delay(30); found = find(text) }
        var action = found
        while (action != null && !action.isClickable) action = action.parent
        requireNotNull(action)
    }
    @Test fun lookupLoadingAndFailureBlockConfirmationUntilRetryCompletes(): Unit = runBlocking {
        val activity = host.launch()
        val state = mutableStateOf(EntityLibraryState())
        var confirmed: String? = null
        var retries = 0
        try {
            host.main { activity.setContent { TidiloTheme(dynamicColor = false) {
                AddEntityDialog({}, { confirmed = it }, "##C", {}, state.value, {
                    retries++; state.value = EntityLibraryState()
                })
            } } }
            assertFalse(button("OK").isEnabled)
            host.main { state.value = EntityLibraryState(loading = false, error = IllegalStateException("Fixture")) }
            assertTrue(button("Retry").performAction(AccessibilityNodeInfo.ACTION_CLICK))
            delay(150); assertEquals(1, retries); assertFalse(button("OK").isEnabled)
            host.main { state.value = EntityLibraryState(loading = false) }
            withTimeout(5000) { while (!button("OK").isEnabled) delay(30) }
            assertTrue(button("OK").performAction(AccessibilityNodeInfo.ACTION_CLICK))
            withTimeout(5000) { while (confirmed == null) delay(30) }
            assertEquals("##C", confirmed)
        } finally { host.main { activity.finish() } }
    }
    @Test fun longSpellingsAndBothChoicesFitAt360dpAndDoubleFontInBothThemes(): Unit = runBlocking {
        val activity = host.launch()
        try {
            for (dark in listOf(false, true)) for (scale in listOf(1f, 2f)) {
                var choice: Boolean? = null
                host.main { activity.setContent { TidiloTheme(darkTheme = dark, dynamicColor = false) {
                    val density = LocalDensity.current.density
                    CompositionLocalProvider(LocalDensity provides Density(density, scale)) {
                        EntityTagConflictDialog("A very long manually entered spelling ".repeat(6),
                            "A very long existing canonical spelling ".repeat(6), {}, { choice = it }, Modifier.width(312.dp))
                    }
                } } }
                host.instrumentation.waitForIdleSync()
                delay(250)
                for ((label, expected) in listOf("Use existing tag" to false, "Replace across all bookmarks" to true)) {
                    val target = button(label)
                    val bounds = Rect().also(target::getBoundsInScreen)
                    val density = host.context.resources.displayMetrics.density
                    assertTrue("48dp action at dark=$dark scale=$scale", bounds.height() / density >= 47f)
                    assertTrue(bounds.left >= 0 && bounds.right <= host.context.resources.displayMetrics.widthPixels)
                    assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    withTimeout(5000) { while (choice != expected) delay(30) }
                    assertEquals(expected, choice)
                }
                host.instrumentation.waitForIdleSync()
                val screenshot = host.instrumentation.uiAutomation.takeScreenshot()
                try {
                    java.io.File(host.context.cacheDir, "entity-conflict-$dark-$scale.png").outputStream().use {
                        screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                } finally { screenshot.recycle() }
            }
        } finally { host.main { activity.finish() } }
    }
    @Test fun displayChipsKeepMeaningfulLeadingHash(): Unit = runBlocking {
        val activity = host.launch()
        try {
            host.main { activity.setContent { TidiloTheme(dynamicColor = false) {
                me.bubblespark.tidilo.ui.screens.library.BookmarkTagEditDialog(null, null, listOf("#C", "C"),
                    onCategoryChanged = {}, onSubcategoryChanged = {}, onAddEntityClick = {},
                    onOpenEntityPickerClick = {}, onConfirm = {}, onDismiss = {})
            } } }
            withTimeout(5000) { while (find("#C") == null) delay(30) }
        } finally { host.main { activity.finish() } }
    }

}
