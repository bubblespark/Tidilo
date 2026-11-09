package me.bubblespark.tidilo.data

import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

internal data class ChromeFrameSummary(
    val count: Int, val droppedCallbacks: Int, val p50Ns: Long?, val p95Ns: Long?,
    val p99Ns: Long?, val maxNs: Long?, val missedDeadlines: Int?
)

internal fun summarizeChromeFrames(samples: LongArray, deadlines: LongArray, dropped: Int): ChromeFrameSummary {
    val sorted = samples.sortedArray()
    fun percentile(rank: Double) = sorted.takeIf { it.isNotEmpty() }?.get(ceil(rank * sorted.size).toInt() - 1)
    val missed = if (samples.isEmpty() || deadlines.size != samples.size || deadlines.any { it <= 0 }) null
        else samples.indices.count { samples[it] > deadlines[it] }
    return ChromeFrameSummary(samples.size, dropped, percentile(.50), percentile(.95), percentile(.99), sorted.lastOrNull(), missed)
}

/** Test-only, bounded primitive storage; summaries are built after the measurement. */
internal class ChromeFrameMetrics(private val window: Window) {
    private val thread = HandlerThread("ChromeFrameMetrics").apply { start() }
    private val handler = Handler(thread.looper)
    private val durations = LongArray(20000)
    private val deadlines = LongArray(20000)
    private var count = 0
    private var dropped = 0
    private val listener = Window.OnFrameMetricsAvailableListener { _, metrics, lost ->
        dropped += lost
        if (count < durations.size) {
            durations[count] = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
            deadlines[count] = metrics.getMetric(FrameMetrics.DEADLINE)
            count++
        } else dropped++
    }
    fun start() { window.addOnFrameMetricsAvailableListener(listener, handler) }
    fun stop(): ChromeFrameSummary {
        window.removeOnFrameMetricsAvailableListener(listener)
        val drained = CountDownLatch(1)
        handler.post { drained.countDown() }
        check(drained.await(5, TimeUnit.SECONDS)) { "Frame callback queue failed to drain" }
        thread.quitSafely()
        check(thread.join(5000).let { !thread.isAlive })
        return summarizeChromeFrames(durations.copyOf(count), deadlines.copyOf(count), dropped)
    }
}
