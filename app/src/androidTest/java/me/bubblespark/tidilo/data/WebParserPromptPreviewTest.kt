package me.bubblespark.tidilo.data

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.config.AppConfig
import me.bubblespark.tidilo.config.AppLanguage
import me.bubblespark.tidilo.parser.WebPageParser
import me.bubblespark.tidilo.service.GeminiTaggingService
import me.bubblespark.tidilo.ui.screens.debug.BodyTextTab
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.*
import org.junit.Test

class WebParserPromptPreviewTest {
    @Test fun promptSelectionDisplaysAndCopiesActualPromptAcrossLanguageAndPageChanges(): Unit = runBlocking {
        val host = HeaderTestHost()
        val previousLanguage = AppConfig.currentLanguage.value
        val page = mutableStateOf(WebPageParser.parseHtml("https://example.com/review", """
            <title>Device review</title><meta name="description" content="Review description">
            <article><p>${"Editorial device content. ".repeat(45)}</p><div class="ad-slot"><p>UnrelatedPromotion</p></div></article>
        """.trimIndent()))
        val mode = mutableStateOf(0)
        val fontScale = mutableStateOf(1f)
        var copied: String? = null
        val activity = host.launch()
        try {
            host.main {
                AppConfig.setLanguage(AppLanguage.ENGLISH)
                activity.setContent {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale.value)) {
                        TidiloTheme(dynamicColor = false) {
                            Column(Modifier.width(360.dp).verticalScroll(rememberScrollState())) {
                                BodyTextTab(page.value, mode.value, { mode.value = it }, { copied = it })
                            }
                        }
                    }
                }
            }
            fun click(node: androidx.compose.ui.semantics.SemanticsNode) = host.main {
                var target = node
                while (target.config.getOrNull(SemanticsActions.OnClick) == null) target = requireNotNull(target.parent)
                assertTrue(target.config[SemanticsActions.OnClick].action?.invoke() == true)
            }
            val promptChip = host.await(activity) { host.text(it, "Prompt") }
            click(promptChip)
            var expected = GeminiTaggingService.buildPrompt(page.value.resolvedUrl, page.value, AppLanguage.ENGLISH)
            host.await(activity) { host.text(it, expected) }
            assertFalse(expected.contains("UnrelatedPromotion"))
            assertEquals(800, me.bubblespark.tidilo.parser.ArticlePreparation.prepare(page.value).bodySnippet.length)
            click(host.await(activity) { host.text(it, "Copy Prompt") })
            assertEquals(expected, copied)
            for (scale in listOf(1f, 2f)) {
                host.main { fontScale.value = scale }
                host.await(activity) { host.text(it, "Prompt") }
                host.main {
                    val chips = host.nodes(activity).filter { node -> node.config.getOrNull(SemanticsProperties.Role) == androidx.compose.ui.semantics.Role.Checkbox }
                    assertEquals("All three choices are measured", 3, chips.size)
                    for (chip in chips) assertTrue("Chip fits narrow viewport", chip.boundsInRoot.right <= 360 * host.context.resources.displayMetrics.density + 1)
                    val heading = host.nodes(activity).first { host.text(it, "Final prompt • body limited to 800 characters") }
                    val copy = host.nodes(activity).first { host.text(it, "Copy Prompt") }
                    assertTrue("Copy action stays below the full-width heading", copy.boundsInRoot.top >= heading.boundsInRoot.bottom)
                }
                host.capture("issue153-prompt-${scale.toInt()}")
            }
            host.main { fontScale.value = 1f; AppConfig.setLanguage(AppLanguage.JAPANESE) }
            expected = GeminiTaggingService.buildPrompt(page.value.resolvedUrl, page.value, AppLanguage.JAPANESE)
            host.await(activity) { host.text(it, expected) }
            host.main { page.value = page.value.copy(title = "Replacement title", readabilityArticle = null, jsoupBodyText = "Fallback\nbody") }
            expected = GeminiTaggingService.buildPrompt(page.value.resolvedUrl, page.value, AppLanguage.JAPANESE)
            host.await(activity) { host.text(it, expected) }
            assertTrue(expected.contains("Content: Fallback body"))
            click(host.await(activity) { host.text(it, "Copy Prompt") })
            assertEquals(expected, copied)
            host.main { mode.value = 1 }
            host.await(activity) { host.text(it, "Fallback\nbody") }
            click(host.await(activity) { host.text(it, "Copy Body") })
            assertEquals("Fallback\nbody", copied)
            host.main { mode.value = 0 }
            host.await(activity) { host.text(it, "No body text extracted with this method.") }
        } finally {
            host.main { AppConfig.setLanguage(previousLanguage); activity.finish() }
        }
    }

    @Test fun httpErrorPagesShowWhyPromptIsUnavailableAndDisableCopy(): Unit = runBlocking {
        val host = HeaderTestHost()
        val page = mutableStateOf(WebPageParser.parseHtml("https://example.com/missing", "<title>Missing</title><p>Not found</p>", statusCode = 404))
        var copied = false
        val activity = host.launch()
        try {
            host.main {
                activity.setContent {
                    TidiloTheme(dynamicColor = false) {
                        BodyTextTab(page.value, 2, {}, { copied = true })
                    }
                }
            }
            for (status in listOf(404, 403, 500)) {
                host.main { page.value = page.value.copy(statusCode = status) }
                host.await(activity) { host.text(it, "Prompt unavailable: HTTP $status. A successful response is required.") }
                val copy = host.await(activity) { host.text(it, "Copy Prompt") }
                host.main {
                    var target = copy
                    while (target.config.getOrNull(SemanticsActions.OnClick) == null) target = requireNotNull(target.parent)
                    assertTrue(target.config.contains(SemanticsProperties.Disabled))
                }
            }
            assertFalse(copied)
            host.main { page.value = page.value.copy(statusCode = 200) }
            val expected = GeminiTaggingService.buildPrompt(page.value.resolvedUrl, page.value, AppConfig.currentLanguage.value)
            host.await(activity) { host.text(it, expected) }
        } finally { host.main { activity.finish() } }
    }
}
