package me.bubblespark.tidilo.data

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.mlkit.genai.common.FeatureStatus
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.config.AiAnalysisMode
import me.bubblespark.tidilo.ui.components.HeaderDefaults
import me.bubblespark.tidilo.ui.screens.settings.AiAnalysisContent
import me.bubblespark.tidilo.ui.screens.settings.SettingsContentMaxWidth
import me.bubblespark.tidilo.ui.screens.settings.SettingsScreen
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SettingsRedesignTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun string(id: Int) = composeRule.activity.getString(id)

    private fun row(label: String): SemanticsNodeInteraction = composeRule.onNode(hasText(label) and hasClickAction())

    @Test fun settingsRowsNavigateAndKeepExistingConfirmationDialogs() {
        var ai = 0
        var privateContent = 0
        var rss = 0
        var summary = 0
        var debug = 0
        composeRule.setContent {
            TidiloTheme(blurEnabled = false, dynamicColor = false) {
                SettingsScreen({}, { ai++ }, onNavigateToPrivateContent = { privateContent++ },
                    onNavigateToRss = { rss++ }, onNavigateToStorage = { summary++ },
                    onNavigateToDebug = { debug++ })
            }
        }
        for (label in listOf(R.string.settings_ai_analysis_title, R.string.settings_private_title)) {
            val node = row(string(label)).fetchSemanticsNode()
            assertEquals(Role.Button, node.config[SemanticsProperties.Role])
        }
        listOf(R.string.settings_ai_analysis_title, R.string.settings_private_title, R.string.rss_title,
            R.string.summary_title, R.string.settings_debug_title).forEach { row(string(it)).performClick() }
        assertEquals(listOf(1, 1, 1, 1, 1), listOf(ai, privateContent, rss, summary, debug))

        row(string(R.string.settings_language_title)).performClick()
        val dialogTitle = composeRule.onNode(hasText(string(R.string.settings_language_dialog_title)))
        dialogTitle.assertExists()
        row(string(R.string.common_close)).performClick()
        dialogTitle.assertDoesNotExist()
    }

    @Test fun settingsAndAiLayoutsRemainScrollableAtLargeFontSizes() {
        var page by mutableStateOf("settings")
        var finalClicks = 0
        composeRule.setContent {
            TidiloTheme(blurEnabled = false, dynamicColor = false) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                    Surface(Modifier.fillMaxSize()) {
                        Box(Modifier.width(360.dp)) {
                            key(page) {
                                if (page == "settings") SettingsScreen({}, {}, onNavigateToDebug = { finalClicks++ })
                                else AiAnalysisContent(
                                    mode = if (page == "ai") AiAnalysisMode.AICORE else AiAnalysisMode.DISABLE,
                                    readiness = AiCoreReadiness.Status(
                                        if (page == "ai") FeatureStatus.AVAILABLE else FeatureStatus.UNAVAILABLE
                                    ), onNavigateBack = {},
                                    onModeChange = { if (page == "disabled") finalClicks++ },
                                    onNavigateToParameters = { if (page == "ai") finalClicks++ })
                            }
                        }
                    }
                }
            }
        }
        val px = composeRule.density.density
        for ((current, finalLabel) in listOf(
            "settings" to R.string.settings_debug_title,
            "ai" to R.string.settings_generate_content_title,
            "disabled" to R.string.settings_ai_analysis_disable
        )) {
            page = current
            finalClicks = 0
            composeRule.waitForIdle()
            scrollToBottom()
            val finalRow = row(string(finalLabel))
            val bounds = finalRow.fetchSemanticsNode().boundsInRoot
            val node = finalRow.fetchSemanticsNode()
            assertTrue("$current rows retain a minimum touch target", bounds.height >= 48f * px)
            assertEquals("$current full row is visible", node.size.height.toFloat(), bounds.height, 1f)
            var limit = 0f
            composeRule.runOnUiThread {
                val decor = composeRule.activity.window.decorView
                val navigation = decor.rootWindowInsets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom
                limit = decor.height - navigation - 31f * px
            }
            assertTrue("$current full row clears navigation bars: $bounds", bounds.bottom <= limit)
            finalRow.performClick()
            assertEquals("$current final row receives a tap", 1, finalClicks)
        }
    }

    @Test fun settingsGroupsAreCenteredAndCappedOnWideWindows() {
        var hostWidth by mutableStateOf(1200.dp)
        composeRule.setContent {
            TidiloTheme(blurEnabled = false, dynamicColor = false) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.requiredWidth(hostWidth).fillMaxHeight().testTag("settings-host")) {
                        SettingsScreen({}, {})
                    }
                }
            }
        }
        for (width in listOf(1200.dp, 360.dp)) {
            hostWidth = width
            composeRule.waitForIdle()
            assertGroupLayout(width)
        }
    }

    private fun assertGroupLayout(hostWidth: Dp) {
        val px = composeRule.density.density
        val hostBounds = composeRule.onNode(hasTestTag("settings-host")).fetchSemanticsNode().boundsInRoot
        assertEquals(hostWidth.value * px, hostBounds.width, 1f)
        val rowBounds = row(string(R.string.settings_language_title)).fetchSemanticsNode().boundsInRoot
        var safeLeft = 0
        var safeRight = 0
        composeRule.runOnUiThread {
            // The scaffold adds horizontal system bar and cutout insets to the 20 dp margins.
            val safe = composeRule.activity.window.decorView.rootWindowInsets.getInsets(
                android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            safeLeft = safe.left
            safeRight = safe.right
        }
        val margin = HeaderDefaults.HorizontalPadding.value * px
        val start = margin + safeLeft
        val end = margin + safeRight
        if (hostWidth > SettingsContentMaxWidth) {
            assertTrue("Groups stay within the 840 dp cap: ${rowBounds.width / px} dp",
                rowBounds.width <= SettingsContentMaxWidth.value * px + 1f)
        } else {
            assertEquals("Compact margins are unchanged", hostBounds.width - start - end, rowBounds.width, 2f)
        }
        assertEquals("Groups are centered in the padded area",
            hostBounds.left + start + (hostBounds.width - start - end) / 2f, rowBounds.center.x, 1f)
    }

    @Test fun sectionTitlesExposeHeadingSemantics() {
        var settings by mutableStateOf(true)
        composeRule.setContent {
            TidiloTheme(blurEnabled = false, dynamicColor = false) {
                if (settings) SettingsScreen({}, {})
                else AiAnalysisContent(mode = AiAnalysisMode.AICORE,
                    readiness = AiCoreReadiness.Status(FeatureStatus.AVAILABLE), onNavigateBack = {},
                    onModeChange = {}, onNavigateToParameters = {})
            }
        }
        assertHeadings(listOf(R.string.settings_section_general, R.string.settings_section_data,
            R.string.settings_section_debug), notHeading = R.string.settings_language_title)
        settings = false
        composeRule.waitForIdle()
        assertHeadings(listOf(R.string.settings_ai_analysis_using_model_title,
            R.string.settings_section_aicore_parameters, R.string.ai_model_status_title),
            notHeading = R.string.settings_ai_analysis_aicore)
    }

    private fun assertHeadings(headings: List<Int>, notHeading: Int) {
        fun nodes(label: Int) = composeRule.onAllNodes(hasText(string(label)), useUnmergedTree = true).fetchSemanticsNodes()
        // A section title can share its text with a row (e.g. "Debug"), so look for the heading among matches.
        for (label in headings) {
            assertTrue("${string(label)} is a heading", nodes(label).any { it.config.contains(SemanticsProperties.Heading) })
        }
        val rows = nodes(notHeading)
        assertTrue("${string(notHeading)} is shown", rows.isNotEmpty())
        assertFalse("${string(notHeading)} is not a heading", rows.any { it.config.contains(SemanticsProperties.Heading) })
    }

    private fun scrollToBottom() {
        val scrollable = composeRule.onAllNodes(hasScrollAction())[0]
        val range = scrollable.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        scrollable.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, range.maxValue() - range.value()) }
        composeRule.waitForIdle()
        val settled = scrollable.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertEquals("Scrolled to the bottom", settled.maxValue(), settled.value(), 1f)
    }
}
