package me.bubblespark.tidilo.data

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import android.view.KeyEvent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.google.mlkit.genai.common.FeatureStatus
import me.bubblespark.tidilo.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.config.AiAnalysisMode
import me.bubblespark.tidilo.config.AppConfig
import me.bubblespark.tidilo.ui.screens.settings.AiAnalysisScreen
import org.junit.Assert.*
import org.junit.Test

class AiAnalysisScreenTest {
    private val host = HeaderTestHost()

    @Test fun unavailableAndCheckingNeverReplaceSavedSelection(): Unit = runBlocking {
        val result = CompletableDeferred<AiCoreReadiness>()
        withScreen({ result.await() }) { activity ->
            host.await(activity) { host.text(it, "Checking AICore availability…") }
            val checking = radio(activity, "AICore")
            assertNotNull(checking.config.getOrNull(SemanticsProperties.Disabled))
            assertTrue(checking.config[SemanticsProperties.Selected])
            assertEquals(androidx.compose.ui.semantics.Role.RadioButton, checking.config[SemanticsProperties.Role])
            assertTrue(checking.boundsInRoot.height >= 48f * host.context.resources.displayMetrics.density)
            assertNull(radio(activity, "Disable").config.getOrNull(SemanticsProperties.Disabled))
            result.complete(AiCoreReadiness.Status(FeatureStatus.UNAVAILABLE))
            host.await(activity) { host.text(it, "AICore is currently unavailable.") }
            assertNotNull(radio(activity, "AICore").config.getOrNull(SemanticsProperties.Disabled))
            assertEquals(AiAnalysisMode.AICORE, AppConfig.aiAnalysisMode.value)
            click(activity, "Disable")
            awaitMode(AiAnalysisMode.DISABLE)
        }
    }

    @Test fun checkFailureKeepsSavedSelection(): Unit = runBlocking {
        withScreen({ AiCoreReadiness.Failed }) { activity ->
            host.await(activity) { host.text(it, "Could not check AICore availability.") }
            val choice = radio(activity, "AICore")
            assertNotNull(choice.config.getOrNull(SemanticsProperties.Disabled))
            assertTrue(choice.config[SemanticsProperties.Selected])
            assertEquals(AiAnalysisMode.AICORE, AppConfig.aiAnalysisMode.value)
        }
    }

    @Test fun modeSwitchHidesOnlyAiSectionsAndPreservesPreferences(): Unit = runBlocking {
        withScreen({ AiCoreReadiness.Status(FeatureStatus.AVAILABLE) }) { activity ->
            host.await(activity) { host.text(it, host.context.getString(R.string.ai_model_ready)) }


            click(activity, "Disable")
            awaitMode(AiAnalysisMode.DISABLE)
            withTimeout(5000) {
                while (true) {
                    var gone = false
                    host.main { gone = host.nodes(activity).none { host.text(it, "GenerateContentRequest") } }
                    if (gone) break
                    delay(30)
                }
            }
            host.await(activity) { host.text(it, "Disable") }

            click(activity, "AICore")
            awaitMode(AiAnalysisMode.AICORE)
            host.await(activity) { host.text(it, "GenerateContentRequest") }
        }
    }

    @Test fun reopeningRechecksReadinessAndBackDoesNotChangeMode(): Unit = runBlocking {
        var checks = 0
        var backs = 0
        val inspect: suspend () -> AiCoreReadiness = {
            AiCoreReadiness.Status(if (++checks > 1) FeatureStatus.AVAILABLE else FeatureStatus.UNAVAILABLE)
        }
        withScreen(inspect) { activity ->
            host.await(activity) { host.text(it, "AICore is currently unavailable.") }
        }
        withScreen(inspect, onBack = { backs++ }) { activity ->
            host.await(activity) { host.text(it, host.context.getString(R.string.ai_model_ready)) }
            assertNull(radio(activity, "AICore").config.getOrNull(SemanticsProperties.Disabled))
            val back = host.await(activity) { host.description(it, host.context.getString(me.bubblespark.tidilo.R.string.common_back)) }
            invokeClick(back)
            assertEquals(1, backs)
            assertEquals(2, checks)
            assertEquals(AiAnalysisMode.AICORE, AppConfig.aiAnalysisMode.value)
        }
    }

    @Test fun parameterRowKeepsItsDestination(): Unit = runBlocking {
        var parameters = 0
        withScreen({ AiCoreReadiness.Status(FeatureStatus.AVAILABLE) }, onParameters = { parameters++ }) { activity ->
            host.await(activity) { host.text(it, "GenerateContentRequest") }
            click(activity, "GenerateContentRequest")
            assertEquals(1, parameters)
            click(activity, "Disable")
            awaitMode(AiAnalysisMode.DISABLE)
        }
    }

    @Test fun cancelledReadinessCheckCannotChangeAnExplicitDisable(): Unit = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        withScreen({
            started.complete(Unit)
            try { kotlinx.coroutines.awaitCancellation() } finally { cancelled.complete(Unit) }
        }) { activity ->
            withTimeout(3000) { started.await() }
            click(activity, "Disable")
            awaitMode(AiAnalysisMode.DISABLE)
            host.main { activity.finish() }
            withTimeout(3000) { cancelled.await() }
            assertEquals(AiAnalysisMode.DISABLE, AppConfig.aiAnalysisMode.value)
        }
    }

    @Test fun keyboardSkipsDisabledAicoreAndActivatesEachEnabledRow(): Unit = runBlocking {
        var ready = false
        try {
            withScreen({
                AiCoreReadiness.Status(if (ready) FeatureStatus.AVAILABLE else FeatureStatus.UNAVAILABLE)
            }) { activity ->
                fun text(id: Int) = activity.getString(id)
                suspend fun focusBack() {
                    val back = host.await(activity) { host.description(it, text(R.string.common_back)) }
                    host.main {
                        val requestFocus = generateSequence(back) { it.parent }
                            .firstNotNullOf { it.config.getOrNull(SemanticsActions.RequestFocus)?.action }
                        assertTrue(requestFocus())
                    }
                }
                suspend fun awaitFocus(label: String) {
                    host.await(activity) { node ->
                        host.text(node, label) && generateSequence(node) { it.parent }.any {
                            it.config.getOrNull(SemanticsProperties.Focused) == true
                        }
                    }
                }

                host.await(activity) { host.text(it, text(R.string.settings_ai_analysis_unavailable)) }
                host.instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_TAB)
                focusBack()
                host.instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_TAB)
                awaitFocus(text(R.string.settings_ai_analysis_disable))
                host.instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
                awaitMode(AiAnalysisMode.DISABLE)

                ready = true
                click(activity, text(R.string.ai_model_check_again))
                host.await(activity) { host.text(it, text(R.string.ai_model_ready)) }
                focusBack()
                host.instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_TAB)
                awaitFocus(text(R.string.settings_ai_analysis_aicore))
                host.instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_SPACE)
                awaitMode(AiAnalysisMode.AICORE)
            }
        } finally {
            host.instrumentation.setInTouchMode(true)
        }
    }

    @Test fun downloadFailureCanBeRetriedAndCompletionKeepsAiDisabled(): Unit = runBlocking {
        var available = false
        var attempts = 0
        val finishDownload = CompletableDeferred<Unit>()
        withScreen(
            checkReadiness = {
                AiCoreReadiness.Status(if (available) FeatureStatus.AVAILABLE else FeatureStatus.DOWNLOADABLE)
            },
            requestDownload = { reportProgress ->
                if (++attempts == 1) AiCoreDownloadResult.FAILED
                else {
                    reportProgress(0.5f)
                    finishDownload.await()
                    available = true
                    AiCoreDownloadResult.COMPLETED
                }
            }
        ) { activity ->
            click(activity, host.context.getString(R.string.settings_ai_analysis_disable))
            awaitMode(AiAnalysisMode.DISABLE)
            val download = host.context.getString(R.string.ai_model_download)
            host.await(activity) { host.text(it, download) }
            assertEquals(0, attempts)
            click(activity, download)
            val failed = host.await(activity) {
                host.text(it, host.context.getString(R.string.ai_model_download_failed))
            }
            assertEquals(androidx.compose.ui.semantics.LiveRegionMode.Polite, failed.config[SemanticsProperties.LiveRegion])
            click(activity, host.context.getString(R.string.common_retry))
            host.await(activity) { host.text(it, host.context.getString(R.string.ai_model_downloading)) }
            host.await(activity) { it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)?.current == 0.5f }
            host.capture("263-settings-downloading")
            finishDownload.complete(Unit)
            host.await(activity) { host.text(it, host.context.getString(R.string.ai_model_ready)) }
            assertEquals(2, attempts)
            assertEquals(AiAnalysisMode.DISABLE, AppConfig.aiAnalysisMode.value)
            assertNull(radio(activity, "AICore").config.getOrNull(SemanticsProperties.Disabled))
            assertTrue(radio(activity, "Disable").config[SemanticsProperties.Selected])
            host.capture("263-settings-ready")
        }
    }

    @Test fun recreationRetainsDownloadFailureUntilExplicitRefresh(): Unit = runBlocking {
        var checks = 0
        val inspect: suspend () -> AiCoreReadiness = {
            checks++
            AiCoreReadiness.Status(FeatureStatus.DOWNLOADABLE)
        }
        val download: suspend ((Float?) -> Unit) -> AiCoreDownloadResult = { AiCoreDownloadResult.FAILED }
        var replacement: ComponentActivity? = null
        try {
            withScreen(inspect, requestDownload = download) { activity ->
                click(activity, host.context.getString(R.string.ai_model_download))
                val failed = host.context.getString(R.string.ai_model_download_failed)
                host.await(activity) { host.text(it, failed) }
                host.main { activity.recreate() }
                withTimeout(8000) {
                    while (replacement == null) {
                        host.main {
                            replacement = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                                .filterIsInstance<ComponentActivity>().firstOrNull { it !== activity }
                        }
                        if (replacement == null) delay(30)
                    }
                }
                val restored = checkNotNull(replacement)
                host.main { restored.setContent { MaterialTheme {
                    AiAnalysisScreen({}, {}, checkAiCoreReadiness = inspect, requestDownload = download)
                } } }
                host.await(restored) { host.text(it, failed) }
                assertEquals(2, checks)
                click(restored, host.context.getString(R.string.ai_model_check_again))
                host.await(restored) { host.text(it, host.context.getString(R.string.ai_model_download)) }
                assertEquals(3, checks)
            }
        } finally {
            host.main { replacement?.finish() }
        }
    }

    @Test fun sdkDownloadInProgressTakesPrecedenceAfterLocalFailure(): Unit = runBlocking {
        var checks = 0
        withScreen(
            checkReadiness = {
                AiCoreReadiness.Status(if (++checks == 1) FeatureStatus.DOWNLOADABLE else FeatureStatus.DOWNLOADING)
            },
            requestDownload = { AiCoreDownloadResult.FAILED }
        ) { activity ->
            click(activity, host.context.getString(R.string.ai_model_download))
            host.await(activity) { host.text(it, host.context.getString(R.string.ai_model_check_again)) }
            host.await(activity) { host.text(it, host.context.getString(R.string.ai_model_downloading)) }
            host.await(activity) {
                it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo) == ProgressBarRangeInfo.Indeterminate
            }
            host.main {
                assertFalse(host.nodes(activity).any {
                    host.text(it, host.context.getString(R.string.ai_model_download_failed))
                })
            }
        }
    }

    private suspend fun withScreen(
        checkReadiness: suspend () -> AiCoreReadiness,
        onBack: () -> Unit = {},
        onParameters: () -> Unit = {},
        requestDownload: suspend ((Float?) -> Unit) -> AiCoreDownloadResult = {
            throw AssertionError("A status check must not download a model")
        },
        block: suspend (ComponentActivity) -> Unit
    ) {
        val mode = AppConfig.aiAnalysisMode.value

        var activity: ComponentActivity? = null
        try {
            host.main {
                AppConfig.setAiAnalysisMode(AiAnalysisMode.AICORE)

            }
            val launched = host.launch()
            activity = launched
            host.main { launched.setContent { MaterialTheme {
                AiAnalysisScreen(onNavigateBack = onBack, onNavigateToGenerateContentRequest = onParameters,
                    checkAiCoreReadiness = checkReadiness, requestDownload = requestDownload)
            } } }
            block(launched)
        } finally {
            host.main {
                try { activity?.finish() } finally {
                    AppConfig.setAiAnalysisMode(mode)

                }
            }
        }
    }

    private suspend fun radio(activity: ComponentActivity, label: String): SemanticsNode {
        var node = host.await(activity) { host.text(it, label) }
        while (node.config.getOrNull(SemanticsProperties.Selected) == null) node = requireNotNull(node.parent)
        return node
    }

    private suspend fun awaitSelected(activity: ComponentActivity, label: String) {
        host.await(activity) { node ->
            host.text(node, label) && generateSequence(node) { it.parent }.any {
                it.config.getOrNull(SemanticsProperties.Selected) == true
            }
        }
    }

    private suspend fun click(activity: ComponentActivity, label: String) = invokeClick(host.await(activity) { host.text(it, label) })

    private fun invokeClick(original: SemanticsNode) = host.main {
        var node = original
        while (node.config.getOrNull(SemanticsActions.OnClick) == null) node = requireNotNull(node.parent)
        assertTrue(node.config[SemanticsActions.OnClick].action?.invoke() == true)
    }

    private suspend fun awaitMode(mode: AiAnalysisMode) = withTimeout(3000) {
        while (AppConfig.aiAnalysisMode.value != mode) delay(20)
    }
}
