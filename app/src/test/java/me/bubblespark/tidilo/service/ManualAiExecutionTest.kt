package me.bubblespark.tidilo.service

import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.GenerativeModel
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.config.AiAnalysisMode
import me.bubblespark.tidilo.data.AiCoreReadiness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualAiExecutionTest {
    @Test
    fun capabilityLostAfterDetailCheckStopsInferenceAndClosesTheClient() = runBlocking {
        var closed = false
        val model = fakeModel({ FeatureStatus.UNAVAILABLE }, { closed = true })

        val error = runCatching {
            runWithAvailableAiCore({ AiAnalysisMode.AICORE }, { model }) {
                throw AssertionError("Inference must not run once the model becomes unavailable")
            }
        }.exceptionOrNull() as AiGenerationBlocked

        assertEquals(AiCoreReadiness.Status(FeatureStatus.UNAVAILABLE), error.readiness)
        assertTrue(closed)
    }

    @Test
    fun optingOutDuringTheStatusCheckBlocksAnOtherwiseReadyModel() = runBlocking {
        var mode = AiAnalysisMode.AICORE
        var closed = false
        val model = fakeModel(check = {
            mode = AiAnalysisMode.DISABLE
            FeatureStatus.AVAILABLE
        }, close = { closed = true })

        val error = runCatching {
            runWithAvailableAiCore({ mode }, { model }) { throw AssertionError("Manual AI was disabled") }
        }.exceptionOrNull() as AiGenerationBlocked

        assertEquals(null, error.readiness)
        assertTrue(closed)
    }

    @Test
    fun aReadyModelIsUsedOnceAndClosed() = runBlocking {
        var closed = false
        val model = fakeModel({ FeatureStatus.AVAILABLE }, { closed = true })

        val result = runWithAvailableAiCore({ AiAnalysisMode.AICORE }, { model }) { client ->
            assertTrue(client === model)
            "proposal"
        }

        assertEquals("proposal", result)
        assertTrue(closed)
    }

    @Test
    fun sdkCapabilityErrorsAfterTheLastCheckUseTheUnavailableRecovery() = runBlocking {
        val errors = listOf(
            GenAiException.ErrorCode.NOT_AVAILABLE,
            GenAiException.ErrorCode.NEEDS_SYSTEM_UPDATE,
            GenAiException.ErrorCode.AICORE_INCOMPATIBLE
        )
        for (code in errors) {
            var closed = false
            val model = fakeModel({ FeatureStatus.AVAILABLE }, { closed = true })
            val result = runCatching {
                runWithAvailableAiCore({ AiAnalysisMode.AICORE }, { model }) {
                    throw GenAiException("Capability changed", null, code)
                }
            }

            val blocked = result.exceptionOrNull() as AiGenerationBlocked
            assertEquals(AiCoreReadiness.Status(FeatureStatus.UNAVAILABLE), blocked.readiness)
            assertTrue(closed)
        }
    }

    @Test
    fun blockedErrorFromInferenceIsRethrownUnwrapped() = runBlocking {
        var closed = false
        val model = fakeModel({ FeatureStatus.AVAILABLE }, { closed = true })
        val blocked = AiGenerationBlocked(null)

        val error = runCatching {
            runWithAvailableAiCore({ AiAnalysisMode.AICORE }, { model }) { throw blocked }
        }.exceptionOrNull()

        assertSame(blocked, error)
        assertNull(blocked.readiness)
        assertTrue(closed)
    }

    private fun fakeModel(check: () -> Int, close: () -> Unit): GenerativeModel =
        Proxy.newProxyInstance(
            GenerativeModel::class.java.classLoader,
            arrayOf(GenerativeModel::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "checkStatus" -> check()
                "close" -> close()
                else -> throw AssertionError("Unexpected model call: ${method.name}")
            }
        } as GenerativeModel
}
