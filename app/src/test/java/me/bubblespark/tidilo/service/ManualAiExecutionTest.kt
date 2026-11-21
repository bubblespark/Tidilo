package me.bubblespark.tidilo.service

import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.GenerativeModel
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.config.AiAnalysisMode
import me.bubblespark.tidilo.data.AiCoreReadiness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualAiExecutionTest {
    @Test
    fun unavailableCapabilityStopsPageFetchAndClosesTheClient() = runBlocking {
        var fetched = false
        var closed = false
        val model = fakeModel({ FeatureStatus.UNAVAILABLE }, { closed = true })

        val error = runCatching {
            runWithAvailableAiCore({ AiAnalysisMode.AICORE }, { model }, prepare = {
                fetched = true
            }) { _, _ ->
                throw AssertionError("Inference must not run once the model becomes unavailable")
            }
        }.exceptionOrNull() as AiGenerationBlocked

        assertEquals(AiCoreReadiness.Status(FeatureStatus.UNAVAILABLE), error.readiness)
        assertFalse(fetched)
        assertTrue(closed)
    }

    @Test
    fun capabilityLostDuringPageFetchStopsInferenceAndClosesTheClient() = runBlocking {
        var status = FeatureStatus.AVAILABLE
        var closed = false
        val preflight = fakeModel({ status }, { closed = true })
        var inferenceClosed = false
        val inference = fakeModel({ status }, { inferenceClosed = true })

        val error = runCatching {
            runWithAvailableAiCore({ AiAnalysisMode.AICORE }, listOf(preflight, inference).iterator()::next, prepare = {
                assertTrue(closed)
                status = FeatureStatus.UNAVAILABLE
            }) { _, _ -> throw AssertionError("Inference must not run after capability is lost") }
        }.exceptionOrNull() as AiGenerationBlocked

        assertEquals(AiCoreReadiness.Status(FeatureStatus.UNAVAILABLE), error.readiness)
        assertTrue(closed)
        assertTrue(inferenceClosed)
    }

    @Test
    fun pageFetchFailureIsPreservedAndClosesTheClient() = runBlocking {
        var closed = false
        val model = fakeModel({ FeatureStatus.AVAILABLE }, { closed = true })
        val pageError = AiProposalPageException("HTTP error 403")

        val error = runCatching {
            runWithAvailableAiCore({ AiAnalysisMode.AICORE }, { model }, prepare = {
                assertTrue(closed)
                throw pageError
            }) { _, _ -> throw AssertionError("Inference must not run after a page failure") }
        }.exceptionOrNull()

        assertSame(pageError, error)
        assertTrue(closed)
    }

    @Test
    fun disableDuringPreflightCheckTakesPriorityOverModelStatus() = runBlocking {
        var mode = AiAnalysisMode.AICORE
        var closed = false
        val model = fakeModel(check = {
            mode = AiAnalysisMode.DISABLE
            FeatureStatus.DOWNLOADABLE
        }, close = { closed = true })

        val error = runCatching {
            runWithAvailableAiCore({ mode }, { model }, prepare = {
                throw AssertionError("Page fetch must not run when manual AI is disabled")
            }) { _, _ -> throw AssertionError("Manual AI was disabled") }
        }.exceptionOrNull() as AiGenerationBlocked

        assertEquals(null, error.readiness)
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
            var preflightClosed = false
            val preflight = fakeModel({ FeatureStatus.AVAILABLE }, { preflightClosed = true })
            val result = runCatching {
                runWithAvailableAiCore({ AiAnalysisMode.AICORE }, listOf(preflight, model).iterator()::next, {
                    assertTrue(preflightClosed)
                    "prompt"
                }) { _, _ ->
                    throw GenAiException("Capability changed", null, code)
                }
            }

            val blocked = result.exceptionOrNull() as AiGenerationBlocked
            assertEquals(AiCoreReadiness.Status(FeatureStatus.UNAVAILABLE), blocked.readiness)
            assertTrue(closed)
        }
    }

    @Test
    fun blockedErrorFromInferenceSurvivesCloseFailure() = runBlocking {
        var closed = false
        val model = fakeModel({ FeatureStatus.AVAILABLE }, {
            closed = true
            error("Close failed")
        })
        var preflightClosed = false
        val preflight = fakeModel({ FeatureStatus.AVAILABLE }, { preflightClosed = true })
        val blocked = AiGenerationBlocked(null)

        val error = runCatching {
            runWithAvailableAiCore({ AiAnalysisMode.AICORE }, listOf(preflight, model).iterator()::next, {
                assertTrue(preflightClosed)
                "prompt"
            }) { _, _ -> throw blocked }
        }.exceptionOrNull()

        assertSame(blocked, error)
        assertNull(blocked.readiness)
        assertTrue(closed)
    }

    @Test
    fun preflightClosesBeforePreparationAndInferenceUsesASeparateClient() = runBlocking {
        val events = mutableListOf<String>()
        fun model(stage: String) = fakeModel(
            check = {
                events += "check $stage"
                FeatureStatus.AVAILABLE
            },
            close = { events += "close $stage" }
        )
        val preflight = model("preflight")
        val inference = model("inference")
        val clients = listOf("preflight" to preflight, "inference" to inference).iterator()

        val result = runWithAvailableAiCore({ AiAnalysisMode.AICORE }, {
            val (stage, client) = clients.next()
            events += "open $stage"
            client
        }, {
            events += "prepare"
            "prompt"
        }) { client, prompt ->
            events += "infer"
            assertSame(inference, client)
            assertEquals("prompt", prompt)
            "proposal"
        }

        assertEquals("prompt" to "proposal", result)
        assertEquals(
            listOf(
                "open preflight", "check preflight", "close preflight", "prepare",
                "open inference", "check inference", "infer", "close inference"
            ),
            events
        )
    }

    @Test
    fun disableDuringInferenceStatusCheckPreservesDisabledReasonAndClosesClient() = runBlocking {
        var mode = AiAnalysisMode.AICORE
        var preflightClosed = false
        var inferenceClosed = false
        val preflight = fakeModel({ FeatureStatus.AVAILABLE }, { preflightClosed = true })
        val inference = fakeModel({
            mode = AiAnalysisMode.DISABLE
            FeatureStatus.UNAVAILABLE
        }, { inferenceClosed = true })
        val error = runCatching {
            runWithAvailableAiCore({ mode }, listOf(preflight, inference).iterator()::next, {
                assertTrue(preflightClosed)
                "prompt"
            }) { _, _ -> error("Unexpected inference") }
        }.exceptionOrNull() as AiGenerationBlocked
        assertNull(error.readiness)
        assertTrue(inferenceClosed)
    }

    @Test
    fun disableAtEntrySkipsClientAndPreparation() = runBlocking {
        val error = runCatching {
            runWithAvailableAiCore({ AiAnalysisMode.DISABLE }, { error("Unexpected client") }, {
                error("Unexpected preparation")
            }) { _, _ -> error("Unexpected inference") }
        }.exceptionOrNull() as AiGenerationBlocked
        assertNull(error.readiness)
    }

    @Test
    fun disableDuringPageFetchSkipsInferenceClient() = runBlocking {
        var mode = AiAnalysisMode.AICORE
        var closed = false
        val preflight = fakeModel({ FeatureStatus.AVAILABLE }, { closed = true })
        val error = runCatching {
            runWithAvailableAiCore({ mode }, listOf(preflight).iterator()::next, {
                assertTrue(closed)
                mode = AiAnalysisMode.DISABLE
            }) { _, _ -> error("Unexpected inference") }
        }.exceptionOrNull() as AiGenerationBlocked
        assertNull(error.readiness)
    }

    @Test
    fun cancellationAtEntryTakesPriorityOverDisable() = runBlocking {
        var thrown: Throwable? = null
        launch {
            cancel()
            thrown = runCatching {
                runWithAvailableAiCore({ AiAnalysisMode.DISABLE }, { error("Unexpected client") }, {
                    error("Unexpected preparation")
                }) { _, _ -> error("Unexpected inference") }
            }.exceptionOrNull()
        }.join()
        assertTrue(thrown is CancellationException)
    }

    @Test
    fun suspendedPageFetchPropagatesCancellationWithoutOpeningInferenceClient() = runBlocking {
        var closed = false
        var thrown: Throwable? = null
        val started = CompletableDeferred<Unit>()
        val preflight = fakeModel({ FeatureStatus.AVAILABLE }, { closed = true })
        val child = launch {
            thrown = runCatching {
                runWithAvailableAiCore({ AiAnalysisMode.AICORE }, listOf(preflight).iterator()::next, {
                    started.complete(Unit)
                    assertTrue(closed)
                    awaitCancellation()
                }) { _, _ -> error("Unexpected inference") }
            }.exceptionOrNull()
        }
        withTimeout(5000) { started.await() }
        child.cancelAndJoin()
        assertTrue(thrown is CancellationException)
    }

    @Test
    fun cancelledPreparationReturningInputStillSkipsInferenceClient() = runBlocking {
        var closed = false
        var thrown: Throwable? = null
        val preflight = fakeModel({ FeatureStatus.AVAILABLE }, { closed = true })
        launch {
            thrown = runCatching {
                runWithAvailableAiCore({ AiAnalysisMode.AICORE }, listOf(preflight).iterator()::next, {
                    assertTrue(closed)
                    currentCoroutineContext().cancel()
                    "prompt"
                }) { _, _ -> error("Unexpected inference") }
            }.exceptionOrNull()
        }.join()
        assertTrue(thrown is CancellationException)
    }

    @Test
    fun readyPreflightCloseFailureDoesNotPreventInference() = runBlocking {
        var closeAttempted = false
        val preflight = fakeModel({ FeatureStatus.AVAILABLE }, {
            closeAttempted = true
            error("Close failed")
        })
        val inference = fakeModel({ FeatureStatus.AVAILABLE }, {})
        val result = runWithAvailableAiCore({ AiAnalysisMode.AICORE }, listOf(preflight, inference).iterator()::next, {
            assertTrue(closeAttempted)
            "prompt"
        }) { client, _ ->
            assertSame(inference, client)
            "proposal"
        }
        assertEquals("proposal", result.second)
    }

    @Test
    fun unavailablePreflightCloseFailurePreservesReadiness() = runBlocking {
        var closeAttempted = false
        val preflight = fakeModel({ FeatureStatus.DOWNLOADABLE }, {
            closeAttempted = true
            error("Close failed")
        })
        val error = runCatching {
            runWithAvailableAiCore({ AiAnalysisMode.AICORE }, listOf(preflight).iterator()::next, {
                error("Unexpected preparation")
            }) { _, _ -> error("Unexpected inference") }
        }.exceptionOrNull() as AiGenerationBlocked
        assertTrue(closeAttempted)
        assertEquals(AiCoreReadiness.Status(FeatureStatus.DOWNLOADABLE), error.readiness)
    }

    @Test
    fun statusFailureHasNoFactoryCause() = runBlocking {
        var closed = false
        val preflight = fakeModel({ error("Status failed") }, { closed = true })
        val error = runCatching {
            runWithAvailableAiCore({ AiAnalysisMode.AICORE }, listOf(preflight).iterator()::next, {
                error("Unexpected preparation")
            }) { _, _ -> error("Unexpected inference") }
        }.exceptionOrNull() as AiGenerationBlocked
        assertTrue(closed)
        assertEquals(AiCoreReadiness.Failed, error.readiness)
        assertNull(error.cause)
    }

    @Test
    fun factoryFailuresPreserveTheirCauseOrPropagateCancellationAtBothStages() = runBlocking {
        for (failDuringPreflight in listOf(true, false)) {
            for (failure in listOf(IllegalStateException("Open failed"), CancellationException("Open cancelled"))) {
                var closed = false
                var prepared = false
                val preflight = fakeModel({ FeatureStatus.AVAILABLE }, { closed = true })
                val error = runCatching {
                    runWithAvailableAiCore({ AiAnalysisMode.AICORE }, {
                        if (failDuringPreflight || prepared) throw failure
                        preflight
                    }, {
                        assertTrue(closed)
                        prepared = true
                    }) { _, _ -> error("Unexpected inference") }
                }.exceptionOrNull()
                assertEquals(!failDuringPreflight, prepared)
                if (failure is CancellationException) {
                    assertSame(failure, error)
                } else {
                    val blocked = error as AiGenerationBlocked
                    assertEquals(AiCoreReadiness.Failed, blocked.readiness)
                    assertSame(failure, blocked.cause)
                }
            }
        }
    }

    @Test
    fun cancellationDuringInferencePropagatesAndClosesInferenceClient() = runBlocking {
        var preflightClosed = false
        var inferenceClosed = false
        var thrown: Throwable? = null
        val started = CompletableDeferred<Unit>()
        val preflight = fakeModel({ FeatureStatus.AVAILABLE }, { preflightClosed = true })
        val inference = fakeModel({ FeatureStatus.AVAILABLE }, { inferenceClosed = true })

        val child = launch {
            thrown = runCatching {
                runWithAvailableAiCore({ AiAnalysisMode.AICORE }, listOf(preflight, inference).iterator()::next, {
                    assertTrue(preflightClosed)
                    "prompt"
                }) { client, _ ->
                    started.complete(Unit)
                    assertSame(inference, client)
                    awaitCancellation()
                }
            }.exceptionOrNull()
        }
        withTimeout(5000) { started.await() }
        child.cancelAndJoin()

        assertTrue(thrown is CancellationException)
        assertTrue(inferenceClosed)
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
