package me.bubblespark.tidilo.data

import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.GenerativeModel
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.startCoroutine

class AiCoreAvailabilityTest {
    @Test fun onlyAvailableIsUsableAndClientsAlwaysClose() = runBlocking {
        for (status in listOf(FeatureStatus.AVAILABLE, FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING,
            FeatureStatus.UNAVAILABLE, -1)) {
            var closed = false
            val model = Proxy.newProxyInstance(GenerativeModel::class.java.classLoader, arrayOf(GenerativeModel::class.java)) { _, method, _ ->
                when (method.name) {
                    "checkStatus" -> status
                    "close" -> { closed = true; null }
                    else -> error("Unexpected inference/download: ${method.name}")
                }
            } as GenerativeModel
            assertEquals(status == FeatureStatus.AVAILABLE, AiCoreAvailability.check(openModel = { model }))
            assertTrue(closed)
        }
    }

    @Test fun initializationFailureIsUnavailable() = runBlocking {
        assertFalse(AiCoreAvailability.check(openModel = { error("AICore unsupported") }))
    }

    @Test fun statusFailureAndTimeoutAreUnavailableAndCloseTheClient() = runBlocking {
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
                    else -> error("Unexpected inference/download: ${method.name}")
                }
            } as GenerativeModel
            assertFalse(AiCoreAvailability.check(openModel = { model }, timeoutMillis = 20))
            assertTrue(closed)
        }
    }
}
