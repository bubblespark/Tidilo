package me.bubblespark.tidilo.data

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
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
        val result = CompletableDeferred<Boolean>()
        withScreen({ result.await() }) { activity ->
            host.await(activity) { host.text(it, "Checking AICore availability…") }
            val checking = radio(activity, "AICore")
            assertNotNull(checking.config.getOrNull(SemanticsProperties.Disabled))
            assertTrue(checking.config[SemanticsProperties.Selected])
            assertEquals(androidx.compose.ui.semantics.Role.RadioButton, checking.config[SemanticsProperties.Role])
            assertTrue(checking.boundsInRoot.height >= 48f * host.context.resources.displayMetrics.density)
            assertNull(radio(activity, "Disable").config.getOrNull(SemanticsProperties.Disabled))
            result.complete(false)
            host.await(activity) { host.text(it, "AICore is currently unavailable.") }
            assertNotNull(radio(activity, "AICore").config.getOrNull(SemanticsProperties.Disabled))
            assertEquals(AiAnalysisMode.AICORE, AppConfig.aiAnalysisMode.value)
            click(activity, "Disable")
            awaitMode(AiAnalysisMode.DISABLE)
        }
    }

    @Test fun modeSwitchHidesOnlyAiSectionsAndPreservesPreferences(): Unit = runBlocking {
        withScreen({ true }) { activity ->
            host.await(activity) { host.text(it, "Summarize and categorize bookmarks using on-device Gemini Nano") }


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
        withScreen({ ++checks > 1 }, onBack = { backs++ }) { activity ->
            host.await(activity) { host.text(it, "AICore is currently unavailable.") }
            host.main { activity.setContent { MaterialTheme {
                AiAnalysisScreen(onNavigateBack = { backs++ }, onNavigateToGenerateContentRequest = {},
                    checkAiCoreAvailable = { ++checks > 1 })
            } } }
            host.await(activity) { host.text(it, "Summarize and categorize bookmarks using on-device Gemini Nano") }
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
        withScreen({ true }, onParameters = { parameters++ }) { activity ->
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
            host.main { activity.setContent { MaterialTheme { androidx.compose.material3.Text("Closed") } } }
            withTimeout(3000) { cancelled.await() }
            assertEquals(AiAnalysisMode.DISABLE, AppConfig.aiAnalysisMode.value)
        }
    }

    private suspend fun withScreen(
        checkAvailable: suspend () -> Boolean,
        onBack: () -> Unit = {},
        onParameters: () -> Unit = {},
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
                    checkAiCoreAvailable = checkAvailable)
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
