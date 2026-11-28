package me.bubblespark.tidilo.data

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.time.LocalTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.data.rss.RssSettings
import me.bubblespark.tidilo.ui.screens.settings.RssSettingsContent
import me.bubblespark.tidilo.ui.screens.settings.StorageLoad
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** RSS trigger-time picker restoration and large-font reachability of the trigger controls. */
class RssTriggerInteractionTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()
    private val activity get() = composeRule.activity

    private fun exists(matcher: SemanticsMatcher) = composeRule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
    private fun description(resource: Int, vararg args: Any) = hasContentDescription(activity.getString(resource, *args))
    private fun clickRow(matcher: SemanticsMatcher) = composeRule.clickNode(composeRule.revealRssRow(matcher))
    private fun clickDialog(label: String) = composeRule.clickNode(composeRule.awaitNode(inRssDialog(hasText(label))))

    /** A clock-face value: the Material dial exposes it as a description without text. */
    private fun dial(value: String) =
        inRssDialog(hasContentDescription(value) and SemanticsMatcher.keyNotDefined(SemanticsProperties.Text))

    /** The picker's minute field shows 15 once the dial accepted the selection. */
    private fun showsMinute15() = exists(inRssDialog(hasText("15") and hasContentDescription("15 minutes")))

    @Test fun pickerDraftSurvivesRestorationAndStaleEditIsDiscarded() {
        val settings = mutableStateOf(RssSettings(listOf(LocalTime.of(18, 30))))
        val restoration = StateRestorationTester(composeRule)
        var updates = 0
        var stage = "mount"
        try {
            restoration.setContent {
                TidiloTheme(darkTheme = false, dynamicColor = false, blurEnabled = false) {
                    RssSettingsContent(StorageLoad.Ready(emptyList()), settings.value, false, null, true,
                        onBack = {}, onRetry = {}, onSettings = { settings.value = it; updates++ },
                        onFeed = { _, _ -> }, onDelete = {}, onFetch = {}, onDismissError = {})
                }
            }
            stage = "add default time"
            clickRow(description(R.string.rss_add_trigger_time))
            clickDialog("Add")
            composeRule.waitUntil(5_000) { settings.value.triggerTimes.size == 2 }
            assertEquals(listOf(LocalTime.of(8, 0), LocalTime.of(18, 30)), settings.value.triggerTimes)
            stage = "open edit"
            clickRow(description(R.string.rss_edit_trigger_time, "18:30"))
            stage = "select hour 12"
            composeRule.clickNode(composeRule.awaitNode(dial("12 hours")))
            stage = "select minute 15"
            composeRule.awaitNode(dial("20 minutes"))
            // The dial can be present while Material still ignores selections during its transition.
            for (attempt in 1..10) {
                if (showsMinute15()) break
                composeRule.onAllNodes(dial("15 minutes")).onFirst().performTouchInput { click() }
                composeRule.waitForIdle()
            }
            assertTrue("Minute 15 was not accepted", showsMinute15())
            stage = "restore and save draft"
            restoration.emulateSavedInstanceStateRestore()
            composeRule.waitUntil(5_000) { showsMinute15() }
            clickDialog("Save")
            composeRule.waitUntil(5_000) { settings.value.triggerTimes.contains(LocalTime.of(12, 15)) }
            assertEquals(listOf(LocalTime.of(8, 0), LocalTime.of(12, 15)), settings.value.triggerTimes)
            assertFalse(settings.value.dailyEnabled)
            assertTrue(settings.value.wifiOnly)
            assertEquals(2, updates)
            stage = "stale edit"
            clickRow(description(R.string.rss_edit_trigger_time, "12:15"))
            composeRule.awaitNode(inRssDialog(hasText("Save")))
            composeRule.runOnUiThread { settings.value = settings.value.copy(triggerTimes = listOf(LocalTime.of(8, 0)), wifiOnly = false) }
            composeRule.waitUntil(5_000) { !exists(inRssDialog(hasText("Save"))) }
            assertEquals(2, updates)
            assertEquals(listOf(LocalTime.of(8, 0)), settings.value.triggerTimes)
            assertFalse(settings.value.wifiOnly)
        } catch (failure: Throwable) {
            throw AssertionError("Picker stage: $stage", failure)
        }
    }

    private data class Case(val dark: Boolean, val scale: Float, val count: Int)

    @Test fun triggerControlsRemainReachableAtLargeFontsInBothThemes(): Unit = runBlocking {
        // Each theme and each font scale reaches both the empty (picker) branch and the delete-button branch.
        val cases = listOf(Case(false, 1f, 0), Case(true, 2f, 0), Case(false, 2f, 3), Case(true, 1f, 1))
        val times = listOf(LocalTime.of(8, 0), LocalTime.NOON, LocalTime.of(18, 30))
        val current = mutableStateOf(cases.first())
        composeRule.setContent {
            val case = current.value
            key(case) {
                TidiloTheme(darkTheme = case.dark, dynamicColor = false, blurEnabled = false) {
                    val density = LocalDensity.current.density
                    CompositionLocalProvider(LocalDensity provides Density(density, case.scale)) {
                        Surface { Box(Modifier.width(360.dp)) {
                            RssSettingsContent(StorageLoad.Ready(emptyList()), RssSettings(times.take(case.count)), false, null, true,
                                onBack = {}, onRetry = {}, onSettings = {}, onFeed = { _, _ -> },
                                onDelete = {}, onFetch = {}, onDismissError = {})
                        } }
                    }
                }
            }
        }
        for (case in cases) {
            composeRule.runOnUiThread { current.value = case }
            composeRule.waitForIdle()
            val scenario = "dark=${case.dark} scale=${case.scale} count=${case.count}"
            val time = times.take(case.count).lastOrNull()
            if (time == null) {
                composeRule.revealRssRow(hasText("Not set"))
                clickRow(description(R.string.rss_add_trigger_time))
                val confirm = composeRule.awaitNode(inRssDialog(hasText("Add")))
                assertTrue("Picker confirmation remains visible at font scale ${case.scale}",
                    confirm.boundsInWindow.height >= confirm.size.height - 1 && confirm.size.height > 0)
                clickDialog("Cancel")
            } else {
                val editLabel = description(R.string.rss_edit_trigger_time, time.toString())
                val removeLabel = description(R.string.rss_remove_trigger_time, time.toString())
                composeRule.revealRssRow(editLabel)
                assertDeleteButton(editLabel, removeLabel, scenario)
            }
        }
    }

    /** Asserts the delete control's geometry and its red/white rendering in a window capture. */
    private suspend fun assertDeleteButton(
        editLabel: SemanticsMatcher,
        removeLabel: SemanticsMatcher,
        scenario: String
    ) {
        val committed = CompletableDeferred<Unit>()
        composeRule.runOnUiThread {
            val decor = activity.window.decorView
            decor.viewTreeObserver.registerFrameCommitCallback { committed.complete(Unit) }
            decor.invalidate()
        }
        withTimeout(5000) { committed.await() }

        val result = CompletableDeferred<Int>()
        lateinit var bitmap: Bitmap
        val density = activity.resources.displayMetrics.density
        val icon = composeRule.awaitNode(removeLabel)
        val remove = icon.clickTarget()
        val edit = composeRule.awaitNode(editLabel).clickTarget()
        for (control in listOf(remove, edit)) {
            val bounds = control.boundsInRoot
            assertTrue("48dp touch target: $scenario $bounds",
                bounds.width >= 48 * density - 1 && bounds.height >= 48 * density - 1)
            assertTrue("Control fits at 360dp: $scenario $bounds", bounds.right <= 360 * density + 1)
        }
        assertTrue("Controls must not overlap: $scenario", remove.boundsInRoot.right <= edit.boundsInRoot.left)
        assertTrue("Delete icon must be fully visible: $scenario ${icon.boundsInWindow}",
            icon.boundsInWindow.height >= icon.size.height - 1)
        val center: Offset = icon.boundsInWindow.center
        try {
            composeRule.runOnUiThread {
                val decor = activity.window.decorView
                bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
                PixelCopy.request(activity.window, bitmap, { code ->
                    if (!result.complete(code)) bitmap.recycle()
                }, Handler(Looper.getMainLooper()))
            }
            val code = withTimeout(5000) { result.await() }
            assertEquals("Window capture: $scenario center=$center", PixelCopy.SUCCESS, code)
            val x = center.x.toInt()
            val y = center.y.toInt()
            val redY = (center.y + 4 * density).toInt()
            assertTrue("Delete icon outside captured window: $scenario center=$center",
                x - 2 >= 0 && x + 2 < bitmap.width && y - 2 >= 0 &&
                    maxOf(y + 2, redY) < bitmap.height)
            val background = bitmap.getPixel(x, redY)
            assertTrue("Delete circle stays red: $scenario color=${Integer.toHexString(background)} center=$center",
                Color.red(background) > 140 && Color.green(background) < 120 && Color.blue(background) < 120)
            val whiteMinus = (-2..2).any { dy ->
                (-2..2).any { dx ->
                    val pixel = bitmap.getPixel(x + dx, y + dy)
                    Color.red(pixel) > 230 && Color.green(pixel) > 230 && Color.blue(pixel) > 230
                }
            }
            assertTrue("Delete minus stays white: $scenario center=$center", whiteMinus)
        } finally {
            // Exactly one side owns the bitmap: a capture that loses to cancel() recycles it in its callback.
            result.cancel()
            if (!result.isCancelled) bitmap.recycle()
        }
    }
}
