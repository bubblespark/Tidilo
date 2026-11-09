package me.bubblespark.tidilo.data

import org.junit.Assert.*
import org.junit.Test

class ChromeFrameMetricsTest {
    @Test fun benchmarkCasesRejectInvalidNamesBeforeSetup() {
        assertEquals(8, parseChromeBenchmarkCases(null).size)
        assertEquals(listOf("modal", "idle"), parseChromeBenchmarkCases("modal,idle"))
        for (invalid in listOf("", "typo", "idle,typo")) {
            try { parseChromeBenchmarkCases(invalid); fail("Invalid case must be rejected") }
            catch (expected: IllegalArgumentException) { assertTrue(requireNotNull(expected.message).contains("Unknown case")) }
        }
    }

    @Test fun emptyFramesAreUnavailableRatherThanZero() {
        val result = summarizeChromeFrames(longArrayOf(), longArrayOf(), 3)
        assertEquals(0, result.count)
        assertNull(result.p95Ns)
        assertNull(result.missedDeadlines)
        assertEquals(3, result.droppedCallbacks)
    }

    @Test fun nearestRankAndDeadlinesAreSummarizedPerWindow() {
        val samples = LongArray(100) { (it + 1) * 1_000_000L }
        val result = summarizeChromeFrames(samples, LongArray(100) { 50_000_000 }, 2)
        assertEquals(50_000_000L, result.p50Ns)
        assertEquals(95_000_000L, result.p95Ns)
        assertEquals(99_000_000L, result.p99Ns)
        assertEquals(100_000_000L, result.maxNs)
        assertEquals(50, result.missedDeadlines)
        assertEquals(2, result.droppedCallbacks)
        assertNull(summarizeChromeFrames(samples, LongArray(100), 0).missedDeadlines)
    }
}
