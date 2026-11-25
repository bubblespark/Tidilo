package me.bubblespark.tidilo.data

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.google.mlkit.genai.common.FeatureStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.config.AiAnalysisMode
import me.bubblespark.tidilo.config.AppConfig
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.service.AiGenerationBlocked
import me.bubblespark.tidilo.service.TaggingResult
import me.bubblespark.tidilo.ui.screens.library.BookmarkAiReviewScreen
import me.bubblespark.tidilo.ui.screens.library.BookmarkDetailScreen
import me.bubblespark.tidilo.ui.screens.library.LibraryViewModel
import me.bubblespark.tidilo.ui.screens.settings.AiAnalysisScreen
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The Detail AI flow across one Activity recreation; the Library ViewModel lives outside the Activity. */
class DetailAiRecreationTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test fun aReadyTapStartsOneReviewAcrossRecreationAndCapabilityLossReturnsToDetail(): Unit = runBlocking {
        val id = "ai-recreation"
        val savedDescription = "Saved summary. ".repeat(75).trim()
        val generated = CompletableDeferred<Result<TaggingResult>>()
        val started = CompletableDeferred<Unit>()
        val readiness = CompletableDeferred<AiCoreReadiness>()
        var checks = 0
        var attempts = 0
        val inspect: suspend () -> AiCoreReadiness = {
            checks++
            readiness.await()
        }
        val generate: suspend () -> Result<TaggingResult> = {
            attempts++
            started.complete(Unit)
            generated.await()
        }
        val previous = AppConfig.aiAnalysisMode.value
        val caches = OfflineVisualCaches()
        val library = withContext(Dispatchers.IO) {
            InMemoryLibrary(composeRule.activity.applicationContext).also { it.awaitReady() }
        }
        try {
            withContext(Dispatchers.IO) {
                library.db.bookmarkDao().insertOrUpdate(BookmarkEntity(id, "AI fixture", "https://example.invalid/$id",
                    savedDescription, createdAt = 1, updatedAt = 1))
            }
            composeRule.runOnUiThread { AppConfig.setAiAnalysisMode(AiAnalysisMode.AICORE) }
            val model = library.viewModel()
            var reviewOpenedOverDialog: Boolean? = null
            fun render() = composeRule.activityRule.scenario.onActivity { activity ->
                activity.setContent { AiFlow(model, id, inspect, generate) { reviewOpenedOverDialog = it } }
            }

            render()
            val action = await(hasContentDescription(string(R.string.detail_action_ai_summary)))
            val invoke = generateSequence(action) { it.parent }
                .firstNotNullOf { it.config.getOrNull(SemanticsActions.OnClick)?.action }
            composeRule.runOnUiThread {
                assertTrue(invoke())
                assertTrue(invoke())
            }
            await(hasText(string(R.string.settings_ai_analysis_checking)))
            readiness.complete(AiCoreReadiness.Status(FeatureStatus.AVAILABLE))
            await(hasText(string(R.string.ai_review_loading_title)))
            withTimeout(8_000) { started.await() }

            composeRule.activityRule.scenario.recreate()
            render()
            await(hasText(string(R.string.ai_review_loading_title)))
            generated.complete(Result.failure(AiGenerationBlocked(AiCoreReadiness.Status(FeatureStatus.UNAVAILABLE))))
            await(hasText(string(R.string.settings_ai_analysis_unavailable)))
            val back = await(hasText(string(R.string.ai_review_back_to_detail)))
            val goBack = generateSequence(back) { it.parent }
                .firstNotNullOf { it.config.getOrNull(SemanticsActions.OnClick)?.action }
            composeRule.runOnUiThread { assertTrue(goBack()) }
            await(hasText("AI fixture"))
            assertEquals("Review must open after the AI dialog closes", false, reviewOpenedOverDialog)
            assertEquals(1, checks)
            assertEquals(1, attempts)
            assertEquals(savedDescription, withContext(Dispatchers.IO) { library.db.bookmarkDao().getBookmarkById(id)?.description })
        } finally {
            composeRule.runOnUiThread { AppConfig.setAiAnalysisMode(previous) }
            caches.close()
            library.close()
        }
    }

    private fun string(id: Int) = composeRule.activity.getString(id)

    private fun nodes(matcher: SemanticsMatcher): List<SemanticsNode> =
        composeRule.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes()

    private fun await(matcher: SemanticsMatcher): SemanticsNode {
        composeRule.waitUntil(8_000) { nodes(matcher).isNotEmpty() }
        return nodes(matcher).first()
    }
}

@androidx.compose.runtime.Composable
private fun AiFlow(
    viewModel: LibraryViewModel,
    id: String,
    inspect: suspend () -> AiCoreReadiness,
    generate: suspend () -> Result<TaggingResult>,
    onReviewNavigation: (dialogVisible: Boolean) -> Unit
) {
    val summaryLabel = androidx.compose.ui.res.stringResource(R.string.detail_action_ai_summary)
    TidiloTheme(dynamicColor = false, blurEnabled = false) {
        Surface(Modifier.fillMaxSize()) {
            val nav = rememberNavController()
            NavHost(nav, startDestination = "detail") {
                composable("detail") {
                    BookmarkDetailScreen(
                        id, viewModel, {},
                        onNavigateToAiReview = {
                            onReviewNavigation(anyWindowShowsText(summaryLabel))
                            nav.navigate("review") { launchSingleTop = true }
                        },
                        checkAiCoreReadiness = inspect
                    )
                }
                composable("review") {
                    BookmarkAiReviewScreen(id, viewModel, { nav.popBackStack() },
                        generateProposal = { _, _, _ -> generate() })
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
