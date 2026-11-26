package me.bubblespark.tidilo.data

import android.view.KeyEvent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mlkit.genai.common.FeatureStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.config.AiAnalysisMode
import me.bubblespark.tidilo.config.AppConfig
import me.bubblespark.tidilo.ui.screens.settings.AiAnalysisScreen
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class AiAnalysisScreenTest {
    @get:Rule val composeRule = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private var previousMode = AiAnalysisMode.AICORE

    // Each mount gets its own ViewModel store, as each navigation entry does in the app.
    private var store = ViewModelStore()
    private var mount by mutableIntStateOf(0)
    private var shown by mutableStateOf(true)

    @Before fun setUp() {
        previousMode = AppConfig.aiAnalysisMode.value
        composeRule.runOnUiThread { AppConfig.setAiAnalysisMode(AiAnalysisMode.AICORE) }
    }

    @After fun tearDown() {
        composeRule.runOnUiThread {
            store.clear()
            AppConfig.setAiAnalysisMode(previousMode)
        }
    }

    @Test fun unavailableAndCheckingNeverReplaceSavedSelection() {
        val result = CompletableDeferred<AiCoreReadiness>()
        show({ result.await() })
        await("Checking AICore availability…")
        val checking = radio("AICore")
        assertNotNull(checking.config.getOrNull(SemanticsProperties.Disabled))
        assertTrue(checking.config[SemanticsProperties.Selected])
        assertEquals(Role.RadioButton, checking.config[SemanticsProperties.Role])
        assertTrue(checking.boundsInRoot.height >= 48f * composeRule.density.density)
        assertNull(radio("Disable").config.getOrNull(SemanticsProperties.Disabled))
        result.complete(AiCoreReadiness.Status(FeatureStatus.UNAVAILABLE))
        await("AICore is currently unavailable.")
        assertNotNull(radio("AICore").config.getOrNull(SemanticsProperties.Disabled))
        assertEquals(AiAnalysisMode.AICORE, AppConfig.aiAnalysisMode.value)
        click("Disable")
        awaitMode(AiAnalysisMode.DISABLE)
    }

    @Test fun checkFailureKeepsSavedSelection() {
        show({ AiCoreReadiness.Failed })
        await("Could not check AICore availability.")
        val choice = radio("AICore")
        assertNotNull(choice.config.getOrNull(SemanticsProperties.Disabled))
        assertTrue(choice.config[SemanticsProperties.Selected])
        assertEquals(AiAnalysisMode.AICORE, AppConfig.aiAnalysisMode.value)
    }

    @Test fun modeSwitchHidesOnlyAiSectionsAndPreservesPreferences() {
        show({ AiCoreReadiness.Status(FeatureStatus.AVAILABLE) })
        await(string(R.string.ai_model_ready))
        click("Disable")
        awaitMode(AiAnalysisMode.DISABLE)
        composeRule.waitUntil(5_000) { nodes(hasText("GenerateContentRequest")).isEmpty() }
        await("Disable")
        click("AICore")
        awaitMode(AiAnalysisMode.AICORE)
        await("GenerateContentRequest")
    }

    @Test fun reopeningRechecksReadinessAndBackDoesNotChangeMode() {
        var checks = 0
        var backs = 0
        show({ AiCoreReadiness.Status(if (++checks > 1) FeatureStatus.AVAILABLE else FeatureStatus.UNAVAILABLE) },
            onBack = { backs++ })
        await("AICore is currently unavailable.")
        remount()
        await(string(R.string.ai_model_ready))
        assertNull(radio("AICore").config.getOrNull(SemanticsProperties.Disabled))
        composeRule.onNode(hasContentDescription(string(R.string.common_back)) and hasClickAction()).performClick()
        assertEquals(1, backs)
        assertEquals(2, checks)
        assertEquals(AiAnalysisMode.AICORE, AppConfig.aiAnalysisMode.value)
    }

    @Test fun parameterRowKeepsItsDestination() {
        var parameters = 0
        show({ AiCoreReadiness.Status(FeatureStatus.AVAILABLE) }, onParameters = { parameters++ })
        await("GenerateContentRequest")
        click("GenerateContentRequest")
        assertEquals(1, parameters)
        click("Disable")
        awaitMode(AiAnalysisMode.DISABLE)
    }

    @Test fun cancelledReadinessCheckCannotChangeAnExplicitDisable(): Unit = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        show({
            started.complete(Unit)
            try { kotlinx.coroutines.awaitCancellation() } finally { cancelled.complete(Unit) }
        })
        withTimeout(3000) { started.await() }
        click("Disable")
        awaitMode(AiAnalysisMode.DISABLE)
        // Leaving the screen disposes it and clears its ViewModel, like finishing its host.
        shown = false
        composeRule.waitForIdle()
        composeRule.runOnUiThread { store.clear() }
        withTimeout(3000) { cancelled.await() }
        assertEquals(AiAnalysisMode.DISABLE, AppConfig.aiAnalysisMode.value)
    }

    @Test fun keyboardSkipsDisabledAicoreAndActivatesEachEnabledRow() {
        var ready = false
        try {
            show({ AiCoreReadiness.Status(if (ready) FeatureStatus.AVAILABLE else FeatureStatus.UNAVAILABLE) })
            val back = composeRule.onNode(hasContentDescription(string(R.string.common_back)) and hasClickAction())
            fun awaitFocus(label: String) =
                composeRule.waitUntil(5_000) {
                    nodes(hasText(label) and (isFocused() or hasAnyAncestor(isFocused()))).isNotEmpty()
                }

            await(string(R.string.settings_ai_analysis_unavailable))
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_TAB)
            back.performSemanticsAction(SemanticsActions.RequestFocus)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_TAB)
            awaitFocus(string(R.string.settings_ai_analysis_disable))
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
            awaitMode(AiAnalysisMode.DISABLE)

            ready = true
            click(string(R.string.ai_model_check_again))
            await(string(R.string.ai_model_ready))
            back.performSemanticsAction(SemanticsActions.RequestFocus)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_TAB)
            awaitFocus(string(R.string.settings_ai_analysis_aicore))
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_SPACE)
            awaitMode(AiAnalysisMode.AICORE)
        } finally {
            instrumentation.setInTouchMode(true)
        }
    }

    @Test fun downloadFailureCanBeRetriedAndCompletionKeepsAiDisabled() {
        var available = false
        var attempts = 0
        val finishDownload = CompletableDeferred<Unit>()
        val progressUpdates = CompletableDeferred<(Float?) -> Unit>()
        show(
            checkReadiness = {
                AiCoreReadiness.Status(if (available) FeatureStatus.AVAILABLE else FeatureStatus.DOWNLOADABLE)
            },
            requestDownload = { reportProgress ->
                if (++attempts == 1) AiCoreDownloadResult.FAILED
                else {
                    progressUpdates.complete(reportProgress)
                    finishDownload.await()
                    available = true
                    AiCoreDownloadResult.COMPLETED
                }
            }
        )
        click(string(R.string.settings_ai_analysis_disable))
        awaitMode(AiAnalysisMode.DISABLE)
        val download = string(R.string.ai_model_download)
        await(download)
        assertEquals(0, attempts)
        click(download)
        val failed = await(string(R.string.ai_model_download_failed))
        assertEquals(androidx.compose.ui.semantics.LiveRegionMode.Polite, failed.config[SemanticsProperties.LiveRegion])
        click(string(R.string.common_retry))
        await(string(R.string.ai_model_downloading))
        awaitProgress { it == ProgressBarRangeInfo.Indeterminate }
        val report = runBlocking { withTimeout(3000) { progressUpdates.await() } }
        report(0.5f)
        awaitProgress { it?.current == 0.5f }
        report(0.75f)
        awaitProgress { it?.current == 0.75f }
        finishDownload.complete(Unit)
        await(string(R.string.ai_model_ready))
        assertEquals(2, attempts)
        assertEquals(AiAnalysisMode.DISABLE, AppConfig.aiAnalysisMode.value)
        assertNull(radio("AICore").config.getOrNull(SemanticsProperties.Disabled))
        assertTrue(radio("Disable").config[SemanticsProperties.Selected])
    }

    @Test fun sdkDownloadInProgressTakesPrecedenceAfterLocalFailure() {
        var checks = 0
        show(
            checkReadiness = {
                AiCoreReadiness.Status(if (++checks == 1) FeatureStatus.DOWNLOADABLE else FeatureStatus.DOWNLOADING)
            },
            requestDownload = { AiCoreDownloadResult.FAILED }
        )
        click(string(R.string.ai_model_download))
        await(string(R.string.ai_model_check_again))
        await(string(R.string.ai_model_downloading))
        awaitProgress { it == ProgressBarRangeInfo.Indeterminate }
        assertFalse(nodes(hasText(string(R.string.ai_model_download_failed))).isNotEmpty())
    }

    private fun show(
        checkReadiness: suspend () -> AiCoreReadiness,
        onBack: () -> Unit = {},
        onParameters: () -> Unit = {},
        requestDownload: suspend ((Float?) -> Unit) -> AiCoreDownloadResult = {
            throw AssertionError("A status check must not download a model")
        }
    ) {
        composeRule.setContent {
            if (shown) key(mount) {
                val owner = remember {
                    val current = store
                    object : ViewModelStoreOwner { override val viewModelStore = current }
                }
                CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                    TidiloTheme(blurEnabled = false, dynamicColor = false) {
                        AiAnalysisScreen(onNavigateBack = onBack, onNavigateToGenerateContentRequest = onParameters,
                            checkAiCoreReadiness = checkReadiness, requestDownload = requestDownload)
                    }
                }
            }
        }
    }

    /** Disposes the current screen and its ViewModel, then shows a fresh instance. */
    private fun remount() {
        composeRule.runOnUiThread {
            store.clear()
            store = ViewModelStore()
            mount++
        }
        composeRule.waitForIdle()
    }

    private fun string(id: Int) = context.getString(id)

    private fun nodes(matcher: SemanticsMatcher): List<SemanticsNode> =
        composeRule.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes()

    private fun await(label: String): SemanticsNode {
        composeRule.waitUntil(5_000) { nodes(hasText(label)).isNotEmpty() }
        return nodes(hasText(label)).first()
    }

    private fun awaitProgress(predicate: (ProgressBarRangeInfo?) -> Boolean) = composeRule.waitUntil(5_000) {
        nodes(SemanticsMatcher("progress") { predicate(it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)) })
            .isNotEmpty()
    }

    private fun radio(label: String): SemanticsNode {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(hasText(label) and isSelectable()).fetchSemanticsNodes().isNotEmpty()
        }
        return composeRule.onAllNodes(hasText(label) and isSelectable()).fetchSemanticsNodes().first()
    }

    private fun click(label: String) {
        await(label)
        composeRule.onNode(hasText(label) and hasClickAction()).performClick()
    }

    private fun awaitMode(mode: AiAnalysisMode) = composeRule.waitUntil(3_000) { AppConfig.aiAnalysisMode.value == mode }
}
