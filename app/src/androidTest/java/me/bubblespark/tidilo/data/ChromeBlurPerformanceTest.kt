package me.bubblespark.tidilo.data

import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.window.DialogWindowProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.config.AppTheme
import me.bubblespark.tidilo.ui.components.TidiloAlertDialog
import me.bubblespark.tidilo.ui.components.TidiloBackground
import me.bubblespark.tidilo.ui.components.LocalAmbientLighting
import me.bubblespark.tidilo.ui.components.AmbientLightingState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import me.bubblespark.tidilo.ui.navigation.LocalBarsVisible
import me.bubblespark.tidilo.ui.screens.library.*
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.File

/** Explicitly invoked benchmark; never runs during the ordinary regression suite. */
class ChromeBlurPerformanceTest {
    @Test fun measure(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        if (args.getString("profile") == null) return@runBlocking
        val profile = requireNotNull(args.getString("profile"))
        require(profile in listOf("baseline", "on", "off"))
        val cases = parseChromeBenchmarkCases(args.getString("case"))
        val repetitions = args.getString("repetitions")?.toInt() ?: 5
        val measureMs = args.getString("durationMs")?.toLong() ?: 10000L
        val modern = args.getString("theme") == "modern"
        val host = HeaderTestHost()
        val fixture = ChromePerformanceFixture(host)
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
        host.instrumentation.addMonitor(monitor)
        var activity: androidx.activity.ComponentActivity? = null
        try {
            fixture.prepare()
            val screen = host.launch()
            activity = screen
            val activation = ChromeFrameMetrics(screen.window)
            activation.start()
            host.main {
                screen.setContent {
                    TidiloTheme(darkTheme = modern, dynamicColor = false,
                        theme = if (modern) AppTheme.MODERN else AppTheme.DEFAULT, blurEnabled = enabled) {
                        val lighting = remember { AmbientLightingState() }
                        CompositionLocalProvider(LocalBarsVisible provides bars, LocalAmbientLighting provides lighting) {
                        Box(Modifier.fillMaxSize()) {
                            TidiloBackground(Modifier.fillMaxSize())
                            ChromePerformanceNavigation(detail,
                                library = { LibraryScreen(fixture.vm, onNavigateToSearch = { detail = true }, onNavigateToDetail = { detail = true }) },
                                bookmarkDetail = { BookmarkDetailScreen(fixture.detailId, fixture.vm, { detail = false }) })
                            if (modal) TidiloAlertDialog(onDismissRequest = { modal = false }, confirmButton = {}, text = {
                                Text("Local measurement dialog")
                                val window = (LocalView.current.parent as? DialogWindowProvider)?.window
                                DisposableEffect(window) {
                                    val collector = if (collectDialog && window != null) ChromeFrameMetrics(window).also { it.start() } else null
                                    if (collector != null) {
                                        activeDialogCollectors.incrementAndGet()
                                        startedDialogCollectors.incrementAndGet()
                                    }
                                    onDispose {
                                        collector?.let {
                                            try { dialogMetrics.add(it.stop()) }
                                            finally { activeDialogCollectors.decrementAndGet() }
                                        }
                                    }
                                }
                            })
                        }
                        }
                    }
                }
            }
            delay(2000)
            val activationSummary = activation.stop()
            for (repetition in 1..repetitions) for (case in cases) {
                check(!File(host.context.cacheDir, "chrome-performance-stop").exists()) { "Measurement stopped at a safe case boundary" }
                host.main { detail = case in listOf("detail", "jump", "modal"); enabled = profile != "off"; modal = false; bars = true }
                delay(3000)
                // Ensure paging/content is ready outside the timed interval.
                host.await(screen) { node -> node.config.getOrNull(SemanticsActions.ScrollBy) != null }
                check(activeDialogCollectors.get() == 0)
                host.main {
                    dialogMetrics.clear()
                    startedDialogCollectors.set(0)
                    collectDialog = true
                }
                val collector = ChromeFrameMetrics(screen.window)
                collector.start()
                val start = SystemClock.elapsedRealtime()
                var tick = 0
                while (SystemClock.elapsedRealtime() - start < measureMs) {
                    when (case) {
                        "library", "detail", "header" -> {
                            host.scroll(screen, if (tick % 40 < 20) 100f else -100f)
                            if (case == "header" && tick % 5 == 0) host.main { bars = !bars }
                        }
                        "jump" -> {
                            val action = host.await(screen) { host.description(it, host.context.getString(R.string.bookmark_open_in_browser)) }
                            host.chromeTap(action)
                        }
                        "navigation" -> if (tick % 5 == 0) host.main { detail = !detail }
                        "modal" -> if (tick % 5 == 0) host.main { modal = !modal }
                        "toggle" -> if (tick < 10) host.main { enabled = !enabled }
                        "idle" -> Unit
                        else -> error("Unknown case $case")
                    }
                    tick++
                    delay(if (case in listOf("library", "detail", "header")) 100 else 250)
                }
                val summary = collector.stop()
                host.main { modal = false }
                withTimeout(5000) { while (activeDialogCollectors.get() != 0) delay(25) }
                host.main { collectDialog = false }
                val completedDialogs = synchronized(dialogMetrics) { dialogMetrics.toList() }
                check(completedDialogs.size == startedDialogCollectors.get()) { "Incomplete dialog metrics" }
                if (case == "modal") check(completedDialogs.isNotEmpty()) { "No dialog collector started" }
                val windows = JSONArray().put(summary.toJson("activity"))
                completedDialogs.forEachIndexed { index, frame -> windows.put(frame.toJson("dialog-$index")) }
                results.put(JSONObject().put("case", case).put("repetition", repetition).put("windows", windows))
            }
            val output = JSONObject().put("profile", profile).put("theme", if (modern) "modern-dark" else "default-light")
                .put("refreshHz", screen.display?.refreshRate).put("warmupMs", 3000).put("durationMs", measureMs)
                .put("activation", activationSummary.toJson("activity"))
                .put("browserIntentsBlocked", monitor.hits).put("runs", results)
            File(host.context.filesDir, "chrome-performance-$profile-${if (modern) "modern" else "default"}.json").writeText(output.toString(2))
        } finally {
            try { activity?.let { screen -> host.main { screen.setContent {}; screen.finish() } } }
            finally {
                try { fixture.close() }
                finally { host.instrumentation.removeMonitor(monitor) }
            }
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
