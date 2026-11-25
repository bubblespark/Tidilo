package me.bubblespark.tidilo.ui.screens.settings

import com.google.mlkit.genai.common.FeatureStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.bubblespark.tidilo.data.AiCoreDownloadResult
import me.bubblespark.tidilo.data.AiCoreReadiness
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AiModelSetupViewModelTest {
    // Unconfined Main resumes each operation as soon as its deferred completes.
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun checkingAndDownloadAreExclusiveAndProgressDoesNotChangeScreenState() = runTest {
        val model = AiModelSetupViewModel()
        val check = CompletableDeferred<AiCoreReadiness>()
        val finish = CompletableDeferred<AiCoreDownloadResult>()
        fun unexpectedDownload(): Nothing = throw AssertionError("Overlapping download")
        suspend fun unexpectedCheck(): Nothing = throw AssertionError("Overlapping status check")

        model.initialize { check.await() }
        model.download(::unexpectedCheck) { unexpectedDownload() }
        model.refresh(::unexpectedCheck)
        val downloadable = AiCoreReadiness.Status(FeatureStatus.DOWNLOADABLE)
        check.complete(downloadable)

        var progress: ((Float?) -> Unit)? = null
        model.download({ downloadable }) { report ->
            progress = report
            finish.await()
        }
        model.refresh(::unexpectedCheck)
        model.download(::unexpectedCheck) { unexpectedDownload() }
        val previous = model.state.value
        checkNotNull(progress)(0.5f)
        assertSame(previous, model.state.value)
        assertEquals(0.5f, requireNotNull(model.progress.value), 0f)
        finish.complete(AiCoreDownloadResult.FAILED)
        assertTrue(model.state.value.downloadFailed)
        assertNull(model.progress.value)
    }
}
