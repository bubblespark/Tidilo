package me.bubblespark.tidilo.data

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import me.bubblespark.tidilo.parser.WebPageParser
import me.bubblespark.tidilo.ui.screens.debug.BodyTextTab
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class WebParserPromptPreviewTest {
    @get:Rule val composeRule = createComposeRule()

    private fun assertText(text: String) {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun clickText(text: String) = composeRule.onNode(hasText(text) and hasClickAction()).performClick()

    @Test fun promptChoiceDisplaysAndCopiesFullFilteredBodyInsteadOfPromptInstructions() {
        val editorial = "Editorial device content. ".repeat(45).trim()
        val page = mutableStateOf(WebPageParser.parseHtml("https://example.com/review", """
            <title>Device review</title><meta name="description" content="Review description">
            <article><p>$editorial</p><div class="ad-slot"><p>UnrelatedPromotion</p></div>
            <div class="af_box"><b>AffiliateWidget for sale</b></div></article>
        """.trimIndent()))
        val mode = mutableStateOf(0)
        val fontScale = mutableStateOf(1f)
        var copied: String? = null
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale.value)) {
                TidiloTheme(blurEnabled = false, dynamicColor = false) {
                    Column(Modifier.width(360.dp).verticalScroll(rememberScrollState())) {
                        BodyTextTab(page.value, mode.value, { mode.value = it }, { copied = it })
                    }
                }
            }
        }
        clickText("Prompt")
        var expected = editorial
        assertText(expected)
        assertFalse(expected.contains("UnrelatedPromotion"))
        assertTrue("Inspection includes text beyond the AI snippet limit", expected.length > 800)
        assertTrue("Raw extraction remains available", requireNotNull(page.value.readabilityArticle).textContent.contains("AffiliateWidget"))
        clickText("Copy Body")
        assertEquals(expected, copied)
        val px = composeRule.density.density
        for (scale in listOf(1f, 2f)) {
            fontScale.value = scale
            composeRule.waitForIdle()
            val chips = composeRule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
                .fetchSemanticsNodes()
            assertEquals("All three choices are measured", 3, chips.size)
            for (chip in chips) assertTrue("Chip fits narrow viewport", chip.boundsInRoot.right <= 360 * px + 1)
            val heading = composeRule.onNode(hasText("Ad-filtered body • before the 800-character limit"),
                useUnmergedTree = true).fetchSemanticsNode()
            val copy = composeRule.onNode(hasText("Copy Body") and hasClickAction()).fetchSemanticsNode()
            assertTrue("Copy action stays below the full-width heading", copy.boundsInRoot.top >= heading.boundsInRoot.bottom)
        }
        fontScale.value = 1f
        page.value = WebPageParser.parseHtml("https://example.com/next", "<title>Next</title><p>Replacement editorial body.</p>")
        expected = "Next Replacement editorial body."
        assertText(expected)
        clickText("Copy Body")
        assertEquals(expected, copied)
        mode.value = 1
        assertText(page.value.jsoupBodyText)
        clickText("Copy Body")
        assertEquals(page.value.jsoupBodyText, copied)
        mode.value = 0
        assertText(requireNotNull(page.value.readabilityArticle).textContent)
    }

    @Test fun filteredBodyRemainsInspectableAndCopyableForHttpErrors() {
        val page = mutableStateOf(WebPageParser.parseHtml("https://example.com/missing", "<title>Missing</title><p>Not found</p>", statusCode = 404))
        var copied: String? = null
        composeRule.setContent {
            TidiloTheme(blurEnabled = false, dynamicColor = false) {
                BodyTextTab(page.value, 2, {}, { copied = it })
            }
        }
        for (status in listOf(404, 403, 500, 200)) {
            page.value = page.value.copy(statusCode = status)
            copied = null
            assertText("Missing Not found")
            composeRule.onNode(hasText("Copy Body") and hasClickAction()).assertIsEnabled().performClick()
            assertEquals("Missing Not found", copied)
        }
    }
}
