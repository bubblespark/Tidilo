package me.bubblespark.tidilo.data

import android.accessibilityservice.AccessibilityService
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ActivityScenario
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.google.mlkit.genai.common.FeatureStatus
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.MainActivity
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.config.AiAnalysisMode
import me.bubblespark.tidilo.config.AppConfig
import me.bubblespark.tidilo.config.AppTheme
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.service.AiGenerationBlocked
import me.bubblespark.tidilo.service.TaggingResult
import me.bubblespark.tidilo.ui.screens.library.BookmarkAiReviewScreen
import me.bubblespark.tidilo.ui.screens.library.BookmarkDetailScreen
import me.bubblespark.tidilo.ui.screens.library.LibraryViewModel
import me.bubblespark.tidilo.ui.screens.settings.AiAnalysisScreen
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailAiAvailabilityTest {
    private val host = HeaderTestHost()
    private val dao = AppDatabase.getInstance(host.context).bookmarkDao()
    private val unavailable = AiCoreReadiness.Status(FeatureStatus.UNAVAILABLE)
    private val dialogVisibleAtNavigation = mutableMapOf<String, Boolean>()

    @Test fun disabledActionOpensSettingsAndReturnsToTheSameEditedNote(): Unit = runBlocking {
        withBookmark(AiAnalysisMode.DISABLE) { scenario, id ->
            val activity = showFlow(scenario, id, inspect = { error("Disabled action must skip preflight") })
            awaitText(activity, "AI fixture")
            host.scroll(activity, 6500f)
            val draft = "Pending note"
            val note = host.await(activity) { it.config.getOrNull(SemanticsActions.SetText) != null }
            host.main { assertTrue(note.config[SemanticsActions.SetText].action?.invoke(AnnotatedString(draft)) == true) }
            fun showsNote(node: SemanticsNode): Boolean =
                node.boundsInRoot.height > 0f && node.config.getOrNull(SemanticsProperties.EditableText)?.text == draft
            val position = await(activity, "visible edited note", ::showsNote).boundsInRoot.top

            val action = aiAction(activity)
            host.main {
                val button = host.nodes(activity, useUnmergedTree = false).first {
                    host.description(it, activity.getString(R.string.detail_action_ai_summary)) &&
                        it.config.getOrNull(SemanticsActions.OnClick) != null
                }
                assertEquals(activity.getString(R.string.ai_analysis_off_state),
                    button.config.getOrNull(SemanticsProperties.StateDescription))
            }
            click(action)
            awaitText(activity, R.string.ai_analysis_off_message)
            host.capture("263-detail-disabled")
            click(awaitText(activity, R.string.ai_analysis_open_settings))
            awaitText(activity, R.string.ai_model_ready)
            assertEquals("Settings must open after the AI dialog closes", false, dialogVisibleAtNavigation["settings"])
            host.capture("263-settings-opened-from-detail")
            assertEquals(AiAnalysisMode.DISABLE, AppConfig.aiAnalysisMode.value)
            waitUntil("Detail toolbar to leave the screen") { !hasDescription(activity, R.string.detail_action_ai_summary) }
            click(await(activity, "AI analysis header Back") {
                host.description(it, activity.getString(R.string.common_back))
            })
            waitUntil("AI analysis settings to close") { !hasText(activity, R.string.ai_model_ready) }
            assertEquals(position, await(activity, "restored edited note", ::showsNote).boundsInRoot.top, 2f)
        }
    }

    @Test fun cancellationIgnoresLateReadinessAndFailedChecksCanBeRetried(): Unit = runBlocking {
        val lateResult = CompletableDeferred<AiCoreReadiness>()
        var checks = 0
        withBookmark(AiAnalysisMode.AICORE) { scenario, id ->
            val activity = showFlow(scenario, id, inspect = {
                when (++checks) {
                    1 -> withContext(NonCancellable) { lateResult.await() }
                    2 -> error("AICore check failed")
                    else -> unavailable
                }
            })
            val action = aiAction(activity)
            val collection = await(activity, "Collection action") {
                host.description(it, activity.getString(R.string.bookmark_collection_action))
            }
            host.main {
                assertTrue(clickAction(collection)())
                assertTrue(clickAction(action)())
            }
            awaitText(activity, R.string.collection_assign_title)
            assertEquals(0, checks)
            assertTrue(host.instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            waitUntil("Collection picker to close") { !hasText(activity, R.string.collection_assign_title) }

            click(aiAction(activity))
            awaitText(activity, R.string.settings_ai_analysis_checking)
            assertTrue(host.instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            waitUntil("readiness dialog to close") { !hasText(activity, R.string.settings_ai_analysis_checking) }
            lateResult.complete(AiCoreReadiness.Status(FeatureStatus.AVAILABLE))
            click(aiAction(activity))
            val error = awaitText(activity, R.string.settings_ai_analysis_check_failed)
            assertEquals(LiveRegionMode.Polite, error.config[SemanticsProperties.LiveRegion])
            host.capture("263-detail-check-failed")
            click(awaitText(activity, R.string.common_retry))
            awaitText(activity, R.string.settings_ai_analysis_unavailable)
            awaitText(activity, R.string.detail_ai_manual_fallback)
            click(awaitText(activity, R.string.common_close))
            assertFalse(hasText(activity, R.string.ai_review_title))
            assertEquals(3, checks)
            assertEquals(AiAnalysisMode.AICORE, AppConfig.aiAnalysisMode.value)
        }
    }

    @Test fun aReadyTapStartsOneReviewAcrossRecreationAndCapabilityLossReturnsToDetail(): Unit = runBlocking {
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
        withBookmark(AiAnalysisMode.AICORE) { scenario, id ->
            val before = showFlow(scenario, id, inspect, generate)
            val action = aiAction(before)
            host.main {
                val invoke = clickAction(action)
                assertTrue(invoke())
                assertTrue(invoke())
            }
            awaitText(before, R.string.settings_ai_analysis_checking)
            readiness.complete(AiCoreReadiness.Status(FeatureStatus.AVAILABLE))
            awaitText(before, R.string.ai_review_loading_title)
            assertEquals("Review must open after the AI dialog closes", false, dialogVisibleAtNavigation["review"])
            withTimeout(8000) { started.await() }
            host.main { before.recreate() }
            var replacement: MainActivity? = null
            waitUntil("replacement Detail host to resume") {
                host.main {
                    replacement = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<MainActivity>().firstOrNull { it !== before }
                }
                replacement != null
            }
            val after = checkNotNull(replacement)
            showFlow(after, id, inspect, generate)
            awaitText(after, R.string.ai_review_loading_title)
            generated.complete(Result.failure(AiGenerationBlocked(unavailable)))
            awaitText(after, R.string.settings_ai_analysis_unavailable)
            host.capture("263-review-unavailable")
            click(awaitText(after, R.string.ai_review_back_to_detail))
            awaitText(after, "AI fixture")
            assertEquals(1, checks)
            assertEquals(1, attempts)
            assertEquals(savedDescription, dao.getBookmarkById(id)?.description)
        }
    }

    @Test fun statusAndSetupAreReadableInBothPresetsAndLanguagesAtLargeFont(): Unit = runBlocking {
        withBookmark(AiAnalysisMode.AICORE) { scenario, id ->
            for (theme in AppTheme.entries) for (dark in listOf(false, true)) for (locale in listOf(Locale.ENGLISH, Locale.JAPANESE)) {
                val variant = "${theme.code}-${locale.language}-${if (dark) "dark" else "light"}"
                lateinit var activity: MainActivity
                lateinit var localized: android.content.Context
                scenario.onActivity {
                    activity = it
                    val configuration = Configuration(it.resources.configuration).apply {
                        setLocale(locale)
                        fontScale = 2f
                    }
                    localized = it.createConfigurationContext(configuration)
                    val viewModel = ViewModelProvider(it)[LibraryViewModel::class.java]
                    it.setContent {
                        key(variant) {
                            CompositionLocalProvider(
                                LocalContext provides localized,
                                LocalConfiguration provides configuration,
                                LocalResources provides localized.resources,
                                LocalDensity provides Density(LocalDensity.current.density, 2f)
                            ) {
                                TidiloTheme(darkTheme = dark, dynamicColor = false, theme = theme) {
                                    Surface(Modifier.fillMaxSize()) {
                                        BookmarkDetailScreen(
                                            id, viewModel, {},
                                            checkAiCoreReadiness = { AiCoreReadiness.Status(FeatureStatus.DOWNLOADABLE) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                click(await(activity, "$variant AI action") {
                    host.description(it, localized.getString(R.string.detail_action_ai_summary))
                })
                awaitText(activity, localized.getString(R.string.ai_model_download_required))
                awaitText(activity, localized.getString(R.string.ai_analysis_open_settings))
                host.capture("263-detail-$variant")
                click(awaitText(activity, localized.getString(R.string.common_close)))

                host.main {
                    activity.setContent {
                        key(variant) {
                            CompositionLocalProvider(
                                LocalContext provides localized,
                                LocalConfiguration provides localized.resources.configuration,
                                LocalResources provides localized.resources,
                                LocalDensity provides Density(LocalDensity.current.density, 2f)
                            ) {
                                TidiloTheme(darkTheme = dark, dynamicColor = false, theme = theme) {
                                    Surface(Modifier.fillMaxSize()) {
                                        AiAnalysisScreen({}, {}, checkAiCoreReadiness = {
                                            AiCoreReadiness.Status(FeatureStatus.DOWNLOADABLE)
                                        })
                                    }
                                }
                            }
                        }
                    }
                }
                awaitText(activity, localized.getString(R.string.ai_model_download))
                host.scroll(activity, 400f)
                host.capture("263-settings-$variant")
            }
        }
    }

    private suspend fun withBookmark(
        mode: AiAnalysisMode,
        exercise: suspend (ActivityScenario<MainActivity>, String) -> Unit
    ) {
        val previous = AppConfig.aiAnalysisMode.value
        val id = "ai-availability-${UUID.randomUUID()}"
        dao.insertOrUpdate(BookmarkEntity(id, "AI fixture", "https://example.invalid/$id", savedDescription,
            createdAt = 1, updatedAt = 1))
        try {
            host.main { AppConfig.setAiAnalysisMode(mode) }
            ActivityScenario.launch(MainActivity::class.java).use { exercise(it, id) }
        } finally {
            host.main { AppConfig.setAiAnalysisMode(previous) }
            dao.deleteById(id)
        }
    }

    private fun showFlow(
        scenario: ActivityScenario<MainActivity>,
        id: String,
        inspect: suspend () -> AiCoreReadiness,
        generate: suspend () -> Result<TaggingResult> = { error("This flow must never start a proposal") }
    ): MainActivity {
        lateinit var activity: MainActivity
        scenario.onActivity { activity = it }
        showFlow(activity, id, inspect, generate)
        return activity
    }

    private fun showFlow(
        activity: MainActivity,
        id: String,
        inspect: suspend () -> AiCoreReadiness,
        generate: suspend () -> Result<TaggingResult>
    ) {
        host.main {
            val viewModel = ViewModelProvider(activity)[LibraryViewModel::class.java]
            activity.setContent {
                TidiloTheme(dynamicColor = false) {
                    Surface(Modifier.fillMaxSize()) {
                        val nav = rememberNavController()
                        NavHost(nav, startDestination = "detail") {
                            composable("detail") {
                                BookmarkDetailScreen(
                                    id, viewModel, {},
                                    onNavigateToAiReview = {
                                        recordDialogVisibility(activity, "review")
                                        nav.navigate("review") { launchSingleTop = true }
                                    },
                                    onNavigateToAiSettings = {
                                        recordDialogVisibility(activity, "settings")
                                        nav.navigate("settings")
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
        }
    }

    private suspend fun aiAction(activity: ComponentActivity) =
        await(activity, "AI action") { host.description(it, activity.getString(R.string.detail_action_ai_summary)) }

    private fun recordDialogVisibility(activity: ComponentActivity, destination: String) {
        val title = activity.getString(R.string.detail_action_ai_summary)
        dialogVisibleAtNavigation[destination] = host.nodes(activity).any { host.text(it, title) }
    }

    private suspend fun awaitText(activity: ComponentActivity, id: Int) = awaitText(activity, activity.getString(id))
    private suspend fun awaitText(activity: ComponentActivity, text: String) =
        await(activity, text) { host.text(it, text) }

    private suspend fun await(activity: ComponentActivity, label: String, predicate: (SemanticsNode) -> Boolean) =
        try {
            host.await(activity, predicate)
        } catch (e: TimeoutCancellationException) {
            var visibleLabels = emptyList<String>()
            host.main {
                visibleLabels = host.nodes(activity).flatMap { node ->
                    node.config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty() +
                        node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                }.map { it.take(100) }.distinct()
            }
            throw AssertionError("Could not find '$label'. Available labels: $visibleLabels", e)
        }

    private fun hasText(activity: ComponentActivity, id: Int): Boolean {
        var found = false
        host.main { found = host.nodes(activity).any { host.text(it, activity.getString(id)) } }
        return found
    }

    private fun hasDescription(activity: ComponentActivity, id: Int): Boolean {
        var found = false
        host.main { found = host.nodes(activity).any { host.description(it, activity.getString(id)) } }
        return found
    }

    private fun click(node: SemanticsNode) = host.main { assertTrue(clickAction(node)()) }

    private fun clickAction(node: SemanticsNode): () -> Boolean = generateSequence(node) { it.parent }
        .firstNotNullOf { it.config.getOrNull(SemanticsActions.OnClick)?.action }

    private suspend fun waitUntil(label: String, condition: () -> Boolean) {
        try {
            withTimeout(8000) { while (!condition()) delay(30) }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError("Timed out waiting for $label", e)
        }
    }

    private companion object {
        val savedDescription = "Saved summary. ".repeat(75).trim()
    }
}
