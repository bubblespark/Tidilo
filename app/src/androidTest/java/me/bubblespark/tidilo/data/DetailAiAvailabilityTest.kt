package me.bubblespark.tidilo.data

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mlkit.genai.common.FeatureStatus
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.config.AiAnalysisMode
import me.bubblespark.tidilo.config.AppConfig
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.ui.screens.library.BookmarkAiReviewScreen
import me.bubblespark.tidilo.ui.screens.library.BookmarkDetailScreen
import me.bubblespark.tidilo.ui.screens.library.LibraryViewModel
import me.bubblespark.tidilo.ui.screens.settings.AiAnalysisScreen
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DetailAiAvailabilityTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()
    private val activity get() = composeRule.activity
    private val unavailable = AiCoreReadiness.Status(FeatureStatus.UNAVAILABLE)
    private val dialogVisibleAtNavigation = mutableMapOf<String, Boolean>()

    @Test fun disabledActionOpensSettingsAndReturnsToTheSameEditedNote() = withBookmark(AiAnalysisMode.DISABLE) { library, id ->
        showFlow(library.viewModel(), id, inspect = { error("Disabled action must skip preflight") })
        await(hasText("AI fixture"))
        val draft = "Pending note"
        // Scroll to the end so the note's position is deterministic.
        val scroll = await(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy))
        composeRule.runOnUiThread { check(scroll.config[SemanticsActions.ScrollBy].action?.invoke(0f, 6500f) == true) }
        composeRule.waitForIdle()
        // SetText without focus: a focused field would raise the IME and shift the layout under the test.
        val note = await(hasSetTextAction())
        composeRule.runOnUiThread { check(note.config[SemanticsActions.SetText].action?.invoke(AnnotatedString(draft)) == true) }
        val editedNote = SemanticsMatcher("visible edited note") {
            it.boundsInRoot.height > 0f && it.config.getOrNull(SemanticsProperties.EditableText)?.text == draft
        }
        await(editedNote)

        // The merged action node carries the state description.
        val aiButton = description(R.string.detail_action_ai_summary) and hasClickAction()
        composeRule.waitUntil(8_000) { composeRule.onAllNodes(aiButton).fetchSemanticsNodes().isNotEmpty() }
        val button = composeRule.onAllNodes(aiButton).fetchSemanticsNodes().first()
        assertEquals(activity.getString(R.string.ai_analysis_off_state), button.config.getOrNull(SemanticsProperties.StateDescription))
        // Where the note sits as Detail is left; returning must restore exactly this.
        val position = await(editedNote).boundsInRoot.top
        click(aiAction())
        await(text(R.string.ai_analysis_off_message))
        click(await(text(R.string.ai_analysis_open_settings)))
        await(text(R.string.ai_model_ready))
        assertEquals("Settings must open after the AI dialog closes", false, dialogVisibleAtNavigation["settings"])
        assertEquals(AiAnalysisMode.DISABLE, AppConfig.aiAnalysisMode.value)
        composeRule.waitUntil(8_000) { nodes(description(R.string.detail_action_ai_summary)).isEmpty() }
        click(await(description(R.string.common_back)))
        composeRule.waitUntil(8_000) { nodes(text(R.string.ai_model_ready)).isEmpty() }
        assertEquals(position, await(editedNote).boundsInRoot.top, 2f)
    }

    @Test fun cancellationIgnoresLateReadinessAndFailedChecksCanBeRetried() = withBookmark(AiAnalysisMode.AICORE) { library, id ->
        val lateResult = CompletableDeferred<AiCoreReadiness>()
        var checks = 0
        showFlow(library.viewModel(), id, inspect = {
            when (++checks) {
                1 -> withContext(NonCancellable) { lateResult.await() }
                2 -> error("AICore check failed")
                else -> unavailable
            }
        })
        val action = aiAction()
        val collection = await(description(R.string.bookmark_collection_action))
        composeRule.runOnUiThread {
            assertTrue(clickAction(collection)())
            assertTrue(clickAction(action)())
        }
        await(text(R.string.collection_assign_title))
        assertEquals(0, checks)
        pressSystemBack()
        composeRule.waitUntil(8_000) { nodes(text(R.string.collection_assign_title)).isEmpty() }

        click(aiAction())
        await(text(R.string.settings_ai_analysis_checking))
        pressSystemBack()
        composeRule.waitUntil(8_000) { nodes(text(R.string.settings_ai_analysis_checking)).isEmpty() }
        lateResult.complete(AiCoreReadiness.Status(FeatureStatus.AVAILABLE))
        click(aiAction())
        val error = await(text(R.string.settings_ai_analysis_check_failed))
        assertEquals(LiveRegionMode.Polite, error.config[SemanticsProperties.LiveRegion])
        click(await(text(R.string.common_retry)))
        await(text(R.string.settings_ai_analysis_unavailable))
        await(text(R.string.detail_ai_manual_fallback))
        click(await(text(R.string.common_close)))
        assertTrue(nodes(text(R.string.ai_review_title)).isEmpty())
        assertEquals(3, checks)
        assertEquals(AiAnalysisMode.AICORE, AppConfig.aiAnalysisMode.value)
    }

    @Test fun statusAndSetupAreReadableInBothLanguagesAtLargeFont() = withBookmark(AiAnalysisMode.AICORE) { library, id ->
        val model = library.viewModel()
        val downloadable: suspend () -> AiCoreReadiness = { AiCoreReadiness.Status(FeatureStatus.DOWNLOADABLE) }
        var locale by mutableStateOf(Locale.ENGLISH)
        var settings by mutableStateOf(false)
        composeRule.setContent {
            val localized = remember(locale) { localized(locale) }
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides localized.resources.configuration,
                LocalResources provides localized.resources,
                LocalDensity provides Density(LocalDensity.current.density, 2f)
            ) {
                TidiloTheme(darkTheme = false, dynamicColor = false, blurEnabled = false) {
                    Surface(Modifier.fillMaxSize()) {
                        if (settings) AiAnalysisScreen({}, {}, checkAiCoreReadiness = downloadable)
                        else BookmarkDetailScreen(id, model, {}, checkAiCoreReadiness = downloadable)
                    }
                }
            }
        }
        for (target in listOf(Locale.ENGLISH, Locale.JAPANESE)) {
            composeRule.runOnUiThread { locale = target; settings = false }
            val localized = localized(target)
            click(await(hasContentDescription(localized.getString(R.string.detail_action_ai_summary))))
            await(hasText(localized.getString(R.string.ai_model_download_required)))
            await(hasText(localized.getString(R.string.ai_analysis_open_settings)))
            click(await(hasText(localized.getString(R.string.common_close))))

            composeRule.runOnUiThread { settings = true }
            await(hasText(localized.getString(R.string.ai_model_download)))
            composeRule.onAllNodes(hasText(localized.getString(R.string.ai_model_download)), useUnmergedTree = true)
                .onFirst().performScrollTo().assertIsDisplayed()
        }
    }

    private fun localized(locale: Locale): Context = activity.createConfigurationContext(
        Configuration(activity.resources.configuration).apply {
            setLocale(locale)
            fontScale = 2f
        }
    )

    /** Runs [exercise] with one bookmark in an in-memory Library, offline caches, and [mode] applied. */
    private fun withBookmark(mode: AiAnalysisMode, exercise: suspend (InMemoryLibrary, String) -> Unit): Unit = runBlocking {
        val id = "ai-availability"
        val previous = AppConfig.aiAnalysisMode.value
        val caches = OfflineVisualCaches()
        val library = withContext(Dispatchers.IO) { InMemoryLibrary(activity.applicationContext).also { it.awaitReady() } }
        try {
            withContext(Dispatchers.IO) {
                library.db.bookmarkDao().insertOrUpdate(BookmarkEntity(id, "AI fixture", "https://example.invalid/$id",
                    savedDescription, createdAt = 1, updatedAt = 1))
            }
            composeRule.runOnUiThread { AppConfig.setAiAnalysisMode(mode) }
            exercise(library, id)
        } finally {
            composeRule.runOnUiThread { AppConfig.setAiAnalysisMode(previous) }
            caches.close()
            library.close()
        }
    }

    private fun showFlow(
        viewModel: LibraryViewModel,
        id: String,
        inspect: suspend () -> AiCoreReadiness
    ) {
        composeRule.setContent {
            TidiloTheme(dynamicColor = false, blurEnabled = false) {
                Surface(Modifier.fillMaxSize()) {
                    val nav = rememberNavController()
                    NavHost(nav, startDestination = "detail") {
                        composable("detail") {
                            BookmarkDetailScreen(
                                id, viewModel, {},
                                onNavigateToAiReview = {
                                    recordDialogVisibility("review")
                                    nav.navigate("review") { launchSingleTop = true }
                                },
                                onNavigateToAiSettings = {
                                    recordDialogVisibility("settings")
                                    nav.navigate("settings")
                                },
                                checkAiCoreReadiness = inspect
                            )
                        }
                        composable("review") {
                            BookmarkAiReviewScreen(id, viewModel, { nav.popBackStack() },
                                generateProposal = { _, _, _ -> error("This flow must never start a proposal") })
                        }
                        composable("settings") {
                            AiAnalysisScreen({ nav.popBackStack() }, {}, checkAiCoreReadiness = {
                                AiCoreReadiness.Status(FeatureStatus.AVAILABLE)
                            })
                        }
                    }
                }
            }
        }
    }

    private fun recordDialogVisibility(destination: String) {
        dialogVisibleAtNavigation[destination] = anyWindowShowsText(activity.getString(R.string.detail_action_ai_summary))
    }

    private fun nodes(matcher: SemanticsMatcher): List<SemanticsNode> =
        composeRule.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes()

    private fun await(matcher: SemanticsMatcher): SemanticsNode {
        composeRule.waitUntil(8_000) { nodes(matcher).isNotEmpty() }
        return nodes(matcher).first()
    }

    private fun text(id: Int) = hasText(activity.getString(id))
    private fun description(id: Int) = hasContentDescription(activity.getString(id))
    private fun aiAction() = await(description(R.string.detail_action_ai_summary))

    private fun clickAction(node: SemanticsNode): () -> Boolean = generateSequence(node) { it.parent }
        .firstNotNullOf { it.config.getOrNull(SemanticsActions.OnClick)?.action }

    private fun click(node: SemanticsNode) {
        val action = clickAction(node)
        composeRule.runOnUiThread { assertTrue(action()) }
        composeRule.waitForIdle()
    }

    /** System Back reaches the focused window, so it also closes dialogs and sheets. */
    private fun pressSystemBack() {
        check(InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
        composeRule.waitForIdle()
    }

    private companion object {
        val savedDescription = "Saved summary. ".repeat(75).trim()
    }
}

/**
 * Whether any Compose window shows [text]. Callable on the UI thread (e.g. in a navigation
 * callback), where ComposeTestRule queries would wait for idle and block.
 */
internal fun anyWindowShowsText(text: String): Boolean {
    fun roots(view: View): List<ViewRootForTest> = when (view) {
        is ViewRootForTest -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { roots(view.getChildAt(it)) }
        else -> emptyList()
    }
    fun contains(root: SemanticsNode): Boolean {
        val pending = mutableListOf(root)
        while (pending.isNotEmpty()) {
            val node = pending.removeAt(pending.lastIndex)
            if (node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true) return true
            pending.addAll(node.children)
        }
        return false
    }
    return WindowInspector.getGlobalWindowViews().flatMap(::roots).any { contains(it.semanticsOwner.unmergedRootSemanticsNode) }
}
