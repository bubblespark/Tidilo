package me.bubblespark.tidilo.data

import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.prompt.GenerativeModel
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.startCoroutine

class AiCoreAvailabilityTest {
    @Test fun modelDownloadReportsProgressAndClosesTheClient() = runBlocking {
        var closed = false
        val progress = mutableListOf<Float?>()
        val model = Proxy.newProxyInstance(
            GenerativeModel::class.java.classLoader,
            arrayOf(GenerativeModel::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "checkStatus" -> FeatureStatus.DOWNLOADABLE
                "download" -> flowOf(
                    DownloadStatus.DownloadStarted(100L),
                    DownloadStatus.DownloadProgress(50L),
                    DownloadStatus.DownloadCompleted
                )
                "close" -> { closed = true; null }
                else -> throw AssertionError("Unexpected model call: ${method.name}")
            }
        } as GenerativeModel

        assertEquals(AiCoreDownloadResult.COMPLETED, downloadAiCoreModel({ progress.add(it) }, { model }))
        assertEquals(listOf(0f, 0.5f), progress)
        assertTrue(closed)
    }

    @Test fun onlyAvailableIsUsableAndClientsAlwaysClose() = runBlocking {
        for (status in listOf(FeatureStatus.AVAILABLE, FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING,
            FeatureStatus.UNAVAILABLE, -1)) {
            var closed = false
            val model = Proxy.newProxyInstance(GenerativeModel::class.java.classLoader, arrayOf(GenerativeModel::class.java)) { _, method, _ ->
                when (method.name) {
                    "checkStatus" -> status
                    "close" -> { closed = true; null }
                    else -> throw AssertionError("Unexpected inference/download: ${method.name}")
                }
            } as GenerativeModel
            val result = AiCoreAvailability.inspect(openModel = { model })
            assertEquals(AiCoreReadiness.Status(status), result)
            assertEquals(status == FeatureStatus.AVAILABLE, result.isAvailable)
            assertTrue(closed)
        }
    }

    @Test fun initializationFailureIsReported() = runBlocking {
        assertEquals(AiCoreReadiness.Failed, AiCoreAvailability.inspect(openModel = { error("Client unavailable") }))
    }

    @Test fun statusFailureAndTimeoutAreReportedAndCloseTheClient() = runBlocking {
        for (timeout in listOf(false, true)) {
            var closed = false
            val model = Proxy.newProxyInstance(GenerativeModel::class.java.classLoader, arrayOf(GenerativeModel::class.java)) { _, method, args ->
                when (method.name) {
                    "close" -> { closed = true; null }
                    "checkStatus" -> {
                        if (!timeout) error("Status failed")
                        @Suppress("UNCHECKED_CAST")
                        val continuation = args!!.last() as Continuation<Int>
                        val suspended: suspend () -> Int = { kotlinx.coroutines.delay(60_000); FeatureStatus.AVAILABLE }
                        suspended.startCoroutine(continuation)
                        COROUTINE_SUSPENDED
                    }
                    else -> throw AssertionError("Unexpected inference/download: ${method.name}")
                }
            } as GenerativeModel
            assertEquals(AiCoreReadiness.Failed, AiCoreAvailability.inspect(openModel = { model }, timeoutMillis = 200))
            assertTrue(closed)
        }
    }

    @Test fun cancellationIsPropagatedAndClosesTheClient() = runBlocking {
        var closed = false
        val model = Proxy.newProxyInstance(
            GenerativeModel::class.java.classLoader,
            arrayOf(GenerativeModel::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "checkStatus" -> throw CancellationException("Check cancelled")
                "close" -> { closed = true; null }
                else -> throw AssertionError("Unexpected model call: ${method.name}")
            }
        } as GenerativeModel

        val result = runCatching { AiCoreAvailability.inspect(openModel = { model }) }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertTrue(closed)
    }
}
