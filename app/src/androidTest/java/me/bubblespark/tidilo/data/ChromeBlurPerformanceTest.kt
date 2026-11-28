package me.bubblespark.tidilo.data

import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.window.DialogWindowProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.MainActivity
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.config.AppTheme
import me.bubblespark.tidilo.ui.components.AmbientLightingState
import me.bubblespark.tidilo.ui.components.LocalAmbientLighting
import me.bubblespark.tidilo.ui.components.TidiloAlertDialog
import me.bubblespark.tidilo.ui.components.TidiloBackground
import me.bubblespark.tidilo.ui.navigation.LocalBarsVisible
import me.bubblespark.tidilo.ui.screens.library.BookmarkDetailScreen
import me.bubblespark.tidilo.ui.screens.library.LibraryScreen
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/** Explicitly invoked benchmark; never runs during the ordinary regression suite. */
class ChromeBlurPerformanceTest {
    @Test
    fun measure(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        if (args.getString("profile") == null) return@runBlocking
        val profile = requireNotNull(args.getString("profile"))
        require(profile in listOf("baseline", "on", "off"))
        val cases = parseChromeBenchmarkCases(args.getString("case"))
        val repetitions = args.getString("repetitions")?.toInt() ?: 5
        val measureMs = args.getString("durationMs")?.toLong() ?: 10000L
        val modern = args.getString("theme") == "modern"
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixture = ChromePerformanceFixture(context)
        val results = JSONArray()
        var detail by mutableStateOf(false)
        var enabled by mutableStateOf(profile != "off")
        var bars by mutableStateOf(true)
        var modal by mutableStateOf(false)
        val dialogMetrics = Collections.synchronizedList(mutableListOf<ChromeFrameSummary>())
        val activeDialogCollectors = AtomicInteger()
        val startedDialogCollectors = AtomicInteger()
        var collectDialog = false
        val monitor = Instrumentation.ActivityMonitor(IntentFilter(Intent.ACTION_VIEW).apply { addDataScheme("https") }, null, true)
        instrumentation.addMonitor(monitor)
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            fixture.prepare()
            val launched = ActivityScenario.launch(MainActivity::class.java)
            scenario = launched
            lateinit var screenWindow: android.view.Window
            lateinit var decorView: View
            var refreshHz: Float? = null
            lateinit var activation: ChromeFrameMetrics
            launched.onActivity { screen ->
                screenWindow = screen.window
                decorView = screenWindow.decorView
                refreshHz = screen.display?.refreshRate
                activation = ChromeFrameMetrics(screenWindow).also { it.start() }
                screen.setContent {
                    TidiloTheme(
                        darkTheme = modern,
                        dynamicColor = false,
                        theme = if (modern) AppTheme.MODERN else AppTheme.DEFAULT,
                        blurEnabled = enabled
                    ) {
                        val lighting = remember { AmbientLightingState() }
                        CompositionLocalProvider(LocalBarsVisible provides bars, LocalAmbientLighting provides lighting) {
                            Box(Modifier.fillMaxSize()) {
                                TidiloBackground(Modifier.fillMaxSize())
                                ChromePerformanceNavigation(
                                    detail,
                                    library = {
                                        LibraryScreen(
                                            fixture.vm,
                                            onNavigateToDetail = { detail = true }
                                        )
                                    },
                                    bookmarkDetail = { BookmarkDetailScreen(fixture.detailId, fixture.vm, { detail = false }) }
                                )
                                if (modal) {
                                    TidiloAlertDialog(onDismissRequest = { modal = false }, confirmButton = {}, text = {
                                        Text("Local measurement dialog")
                                        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
                                        DisposableEffect(window) {
                                            val collector = if (collectDialog && window != null) {
                                                ChromeFrameMetrics(window).also { it.start() }
                                            } else {
                                                null
                                            }
                                            if (collector != null) {
                                                activeDialogCollectors.incrementAndGet()
                                                startedDialogCollectors.incrementAndGet()
                                            }
                                            onDispose {
                                                collector?.let {
                                                    try {
                                                        dialogMetrics.add(it.stop())
                                                    } finally {
                                                        activeDialogCollectors.decrementAndGet()
                                                    }
                                                }
                                            }
                                        }
                                    })
                                }
                            }
                        }
                    }
                }
            }
            delay(2000)
            val activationSummary = activation.stop()
            for (repetition in 1..repetitions) for (case in cases) {
                check(!File(context.cacheDir, "chrome-performance-stop").exists()) { "Measurement stopped at a safe case boundary" }
                instrumentation.runOnMainSync {
                    detail = case in listOf("detail", "jump", "modal")
                    enabled = profile != "off"
                    modal = false
                    bars = true
                }
                delay(3000)
                // Ensure paging/content is ready outside the timed interval.
                awaitNode(instrumentation, decorView) { node -> node.config.getOrNull(SemanticsActions.ScrollBy) != null }
                check(activeDialogCollectors.get() == 0)
                instrumentation.runOnMainSync {
                    dialogMetrics.clear()
                    startedDialogCollectors.set(0)
                    collectDialog = true
                }
                val collector = ChromeFrameMetrics(screenWindow)
                collector.start()
                val start = SystemClock.elapsedRealtime()
                var tick = 0
                while (SystemClock.elapsedRealtime() - start < measureMs) {
                    when (case) {
                        "library", "detail", "header" -> {
                            scroll(instrumentation, decorView, if (tick % 40 < 20) 100f else -100f)
                            if (case == "header" && tick % 5 == 0) instrumentation.runOnMainSync { bars = !bars }
                        }
                        "jump" -> {
                            val openInBrowser = context.getString(R.string.bookmark_open_in_browser)
                            val action = awaitNode(instrumentation, decorView) {
                                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(openInBrowser) == true
                            }
                            instrumentation.chromeTap(action)
                        }
                        "navigation" -> if (tick % 5 == 0) instrumentation.runOnMainSync { detail = !detail }
                        "modal" -> if (tick % 5 == 0) instrumentation.runOnMainSync { modal = !modal }
                        "toggle" -> if (tick < 10) instrumentation.runOnMainSync { enabled = !enabled }
                        "idle" -> Unit
                        else -> error("Unknown case $case")
                    }
                    tick++
                    delay(if (case in listOf("library", "detail", "header")) 100 else 250)
                }
                val summary = collector.stop()
                instrumentation.runOnMainSync { modal = false }
                withTimeout(5000) { while (activeDialogCollectors.get() != 0) delay(100) }
                instrumentation.runOnMainSync { collectDialog = false }
                val completedDialogs = synchronized(dialogMetrics) { dialogMetrics.toList() }
                check(completedDialogs.size == startedDialogCollectors.get()) { "Incomplete dialog metrics" }
                if (case == "modal") check(completedDialogs.isNotEmpty()) { "No dialog collector started" }
                val windows = JSONArray().put(summary.toJson("activity"))
                completedDialogs.forEachIndexed { index, frame -> windows.put(frame.toJson("dialog-$index")) }
                results.put(JSONObject().put("case", case).put("repetition", repetition).put("windows", windows))
            }
            val output = JSONObject().put("profile", profile).put("theme", if (modern) "modern-dark" else "default-light")
                .put("refreshHz", refreshHz).put("warmupMs", 3000).put("durationMs", measureMs)
                .put("activation", activationSummary.toJson("activity"))
                .put("browserIntentsBlocked", monitor.hits).put("runs", results)
            File(context.filesDir, "chrome-performance-$profile-${if (modern) "modern" else "default"}.json").writeText(output.toString(2))
        } finally {
            try {
                scenario?.let { activeScenario ->
                    runCatching { activeScenario.onActivity { it.setContent {} } }
                    activeScenario.close()
                }
            } finally {
                try {
                    fixture.close()
                } finally {
                    instrumentation.removeMonitor(monitor)
                }
            }
        }
    }

    private fun collectNodes(decorView: View): List<SemanticsNode> {
        val roots = mutableListOf<ViewRootForTest>()
        val pendingViews = mutableListOf(decorView)
        while (pendingViews.isNotEmpty()) {
            when (val view = pendingViews.removeAt(pendingViews.lastIndex)) {
                is ViewRootForTest -> roots.add(view)
                is ViewGroup -> for (i in 0 until view.childCount) pendingViews.add(view.getChildAt(i))
            }
        }
        val nodes = mutableListOf<SemanticsNode>()
        val stack = mutableListOf<SemanticsNode>()
        roots.distinct().forEach { root ->
            stack.add(root.semanticsOwner.unmergedRootSemanticsNode)
            while (stack.isNotEmpty()) {
                val node = stack.removeAt(stack.lastIndex)
                nodes.add(node)
                stack.addAll(node.children)
            }
        }
        return nodes
    }

    private suspend fun awaitNode(
        instrumentation: Instrumentation,
        decorView: View,
        predicate: (SemanticsNode) -> Boolean
    ): SemanticsNode = withTimeout(8000) {
        var found: SemanticsNode? = null
        while (found == null) {
            instrumentation.runOnMainSync { found = collectNodes(decorView).firstOrNull(predicate) }
            if (found == null) delay(100)
        }
        requireNotNull(found)
    }

    private fun scroll(instrumentation: Instrumentation, decorView: View, amount: Float) {
        instrumentation.runOnMainSync {
            val scrollNode = collectNodes(decorView).first { it.config.getOrNull(SemanticsActions.ScrollBy) != null }
            check(scrollNode.config[SemanticsActions.ScrollBy].action?.invoke(0f, amount) == true)
        }
    }
}

private fun ChromeFrameSummary.toJson(window: String): JSONObject = JSONObject().put("window", window)
    .put("count", count).put("droppedCallbacks", droppedCallbacks)
    .put("p50Ns", p50Ns ?: JSONObject.NULL).put("p95Ns", p95Ns ?: JSONObject.NULL)
    .put("p99Ns", p99Ns ?: JSONObject.NULL).put("maxNs", maxNs ?: JSONObject.NULL)
    .put("missedDeadlines", missedDeadlines ?: JSONObject.NULL)

internal fun parseChromeBenchmarkCases(argument: String?): List<String> {
    val supported = listOf("idle", "library", "detail", "jump", "header", "navigation", "modal", "toggle")
    val cases = argument?.split(",") ?: supported
    require(cases.all { it in supported }) { "Unknown case in $cases" }
    return cases
}
