package me.bubblespark.tidilo.data

import androidx.work.ListenableWorker.Result
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.data.rss.RssWifiUnavailable
import me.bubblespark.tidilo.data.rss.runRssFetchAttempt
import me.bubblespark.tidilo.data.rss.selectRssNetwork
import org.junit.Assert.*
import org.junit.Test

class RssAcquisitionPolicyTest {
    @Test fun timeSliceContinuationDoesNotExhaustDurableArticleRetries() = runBlocking {
        var aligned = false
        assertEquals(Result.retry(), runRssFetchAttempt(10, { false }, { aligned = true }))
        assertFalse(aligned)
        assertEquals(Result.retry(), runRssFetchAttempt(10, { throw me.bubblespark.tidilo.data.rss.RssAcquisitionYield() }, { aligned = true }))
        assertFalse(aligned)
    }
    @Test fun wifiWinsOverCellularRegardlessOfOrderAndPolicyStillRestrictsFallback() {
        for (candidates in listOf(listOf("cell" to false, "wifi" to true), listOf("wifi" to true, "cell" to false))) {
            assertEquals("wifi", selectRssNetwork(candidates, false))
            assertEquals("wifi", selectRssNetwork(candidates, true))
        }
        assertNull(selectRssNetwork(listOf("cell" to false), true))
        assertEquals("cell", selectRssNetwork(listOf("cell" to false), false))
        assertNull(selectRssNetwork(emptyList<Pair<String, Boolean>>(), false))
    }

    @Test fun failedRequestsStopAfterFiveAttemptsWhileWifiWaitAndCancellationRemainDistinct() = runBlocking {
        var aligned = 0
        val align: suspend () -> Unit = { aligned++ }
        assertEquals(Result.retry(), runRssFetchAttempt(3, { false }, align))
        assertEquals(Result.retry(), runRssFetchAttempt(3, { throw IOException() }, align))
        assertEquals(0, aligned)
        assertEquals(Result.retry(), runRssFetchAttempt(4, { false }, align))
        assertEquals(Result.success(), runRssFetchAttempt(4, { throw IOException() }, align))
        assertEquals(Result.success(), runRssFetchAttempt(0, { true }, align))
        assertEquals(2, aligned)
        assertEquals(Result.retry(), runRssFetchAttempt(10, { throw RssWifiUnavailable() }, align))
        try {
            runRssFetchAttempt(10, { throw CancellationException("cancel") }, align)
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals(2, aligned)
    }
}
