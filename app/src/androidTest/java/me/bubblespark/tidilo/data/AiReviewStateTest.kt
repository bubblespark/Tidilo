package me.bubblespark.tidilo.data

import androidx.lifecycle.SavedStateHandle
import com.google.mlkit.genai.common.FeatureStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.config.AiAnalysisMode
import me.bubblespark.tidilo.service.AiProposalPageException
import me.bubblespark.tidilo.service.TaggingResult
import me.bubblespark.tidilo.ui.screens.library.AiReviewUiState
import me.bubblespark.tidilo.ui.screens.library.BookmarkAiReviewViewModel
import me.bubblespark.tidilo.ui.screens.library.DetailAiActionViewModel
import me.bubblespark.tidilo.ui.screens.library.DetailAiState
import me.bubblespark.tidilo.ui.screens.settings.AiModelSetupViewModel
import org.junit.Assert.*
import org.junit.Test

class AiReviewStateTest {
    @Test fun restoredAttemptWaitsForAnExplicitRetry(): Unit = runBlocking {
        withContext(Dispatchers.Main) {
            val model = BookmarkAiReviewViewModel(SavedStateHandle(mapOf("attemptStarted" to true)))
            val proposal = TaggingResult("Page", "Summary", "", "", emptyList())

            model.generate { throw AssertionError("Restoring review must not start generation") }
            assertEquals(AiReviewUiState.Interrupted, model.state.value)

            model.generate(retry = true) { Result.success(proposal) }
            assertEquals(AiReviewUiState.Success(proposal), model.state.value)
        }
    }

    @Test fun onlyPageAcquisitionFailuresOfferBrowserRecovery(): Unit = runBlocking {
        withContext(Dispatchers.Main) {
            val model = BookmarkAiReviewViewModel(SavedStateHandle())
            model.generate { Result.failure(AiProposalPageException("HTTP error 403")) }
            assertEquals(AiReviewUiState.Error(R.string.ai_review_page_unavailable), model.state.value)

            model.generate(retry = true) { Result.failure(IllegalArgumentException("Invalid proposal")) }
            assertEquals(AiReviewUiState.Error(R.string.ai_review_try_again), model.state.value)
        }
    }

    @Test fun disablingAfterReadyPreventsDetailFromOpeningReview(): Unit = runBlocking {
        withContext(Dispatchers.Main) {
            val model = DetailAiActionViewModel()
            model.check({ AiAnalysisMode.AICORE }) { AiCoreReadiness.Status(FeatureStatus.AVAILABLE) }
            assertEquals(DetailAiState.Ready, model.state.value)
            assertFalse(model.consumeReady(AiAnalysisMode.DISABLE))
            assertEquals(DetailAiState.Disabled, model.state.value)
        }
    }

    @Test fun checkingAndDownloadAreExclusiveAndProgressDoesNotChangeScreenState(): Unit = runBlocking {
        withContext(Dispatchers.Main) {
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
            // Let the ViewModel resume the check on Main before starting a download.
            kotlinx.coroutines.yield()

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
            kotlinx.coroutines.yield()
            assertTrue(model.state.value.downloadFailed)
            assertNull(model.progress.value)
        }
    }
}
