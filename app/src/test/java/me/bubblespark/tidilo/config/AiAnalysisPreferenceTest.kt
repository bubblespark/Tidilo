package me.bubblespark.tidilo.config

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AiAnalysisPreferenceTest {
    @Test fun failedPackageInspectionStillCompletesInitializationWithExistingDefault() = runBlocking {
        val saved = mutableListOf<AiAnalysisMode>()
        val preference = AiAnalysisPreference(null) { saved.add(it) }
        val initialized = async {
            preference.initialize(detectFreshInstallation { error("Package inspection failed") }) {
                error("Unknown install history must not probe AICore")
            }
        }
        kotlinx.coroutines.withTimeout(1000) { initialized.await() }
        assertEquals(listOf(AiAnalysisMode.AICORE), saved)
        assertEquals(AiAnalysisMode.AICORE, preference.mode.value)
    }

    @Test fun installationDetectionPreservesCancellationAndRecognizesUpdates() {
        assertTrue(detectFreshInstallation { 100L to 100L })
        assertFalse(detectFreshInstallation { 100L to 200L })
        val cancellation = kotlinx.coroutines.CancellationException("Cancelled")
        try {
            detectFreshInstallation { throw cancellation }
            fail("Cancellation must propagate")
        } catch (caught: kotlinx.coroutines.CancellationException) {
            assertSame(cancellation, caught)
        }
    }
    @Test fun freshInstallationUsesAvailableStatusOnlyOnce() = runBlocking {
        for (available in listOf(true, false)) {
            val saved = mutableListOf<AiAnalysisMode>()
            val preference = AiAnalysisPreference(null) { saved.add(it) }
            var calls = 0
            preference.initialize(true) { calls++; available }
            preference.initialize(true) { error("Must not check on subsequent launches") }
            val expected = if (available) AiAnalysisMode.AICORE else AiAnalysisMode.DISABLE
            assertEquals(expected, preference.mode.value)
            assertEquals(listOf(expected), saved)
            assertEquals(1, calls)
        }
    }

    @Test fun upgradesAndExplicitSelectionsNeverGetOverwritten() = runBlocking {
        val saved = mutableListOf<AiAnalysisMode>()
        val upgrade = AiAnalysisPreference(null) { saved.add(it) }
        upgrade.initialize(false) { error("Do not probe existing installs") }
        assertEquals(listOf(AiAnalysisMode.AICORE), saved)
        for (mode in AiAnalysisMode.entries) {
            val existing = AiAnalysisPreference(mode) { error("Do not overwrite saved choices") }
            existing.initialize(true) { error("Do not check saved choices") }
            assertEquals(mode, existing.mode.value)
        }
    }

    @Test fun manualChoiceWinsWhileFirstAvailabilityCheckIsInFlight() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val saved = mutableListOf<AiAnalysisMode>()
        val preference = AiAnalysisPreference(null) { saved.add(it) }
        val initialization = async {
            preference.initialize(true) { started.complete(Unit); release.await(); true }
        }
        started.await()
        preference.setMode(AiAnalysisMode.DISABLE)
        release.complete(Unit)
        initialization.await()
        assertEquals(listOf(AiAnalysisMode.DISABLE), saved)
        assertEquals(AiAnalysisMode.DISABLE, preference.mode.value)
    }

    @Test fun cancelledInitializationDoesNotPersistADecision() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val saved = mutableListOf<AiAnalysisMode>()
        val preference = AiAnalysisPreference(null) { saved.add(it) }
        val initialization = async {
            preference.initialize(true) { started.complete(Unit); kotlinx.coroutines.awaitCancellation() }
        }
        started.await()
        initialization.cancel()
        initialization.join()
        assertTrue(saved.isEmpty())
        preference.initialize(true) { false }
        assertEquals(listOf(AiAnalysisMode.DISABLE), saved)
    }
}
