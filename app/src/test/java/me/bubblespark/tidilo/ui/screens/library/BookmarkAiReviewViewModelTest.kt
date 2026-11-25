package me.bubblespark.tidilo.ui.screens.library

import androidx.lifecycle.SavedStateHandle
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException.ErrorCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.data.AiCoreReadiness
import me.bubblespark.tidilo.service.AiCoreExecutionException
import me.bubblespark.tidilo.service.AiGenerationBlocked
import me.bubblespark.tidilo.service.AiProposalPageException
import me.bubblespark.tidilo.service.TaggingResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BookmarkAiReviewViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun restoredAttemptWaitsForAnExplicitRetry() = runTest {
        val model = BookmarkAiReviewViewModel(SavedStateHandle(mapOf("attemptStarted" to true)))
        val proposal = TaggingResult("Page", "Summary", "", "", emptyList())

        model.generate { throw AssertionError("Restoring review must not start generation") }
        assertEquals(AiReviewUiState.RestoredAttempt, model.state.value)

        model.generate(retry = true) { Result.success(proposal) }
        assertEquals(AiReviewUiState.Success(proposal), model.state.value)
    }

    @Test fun completedAttemptRestoresWithoutStartingAnotherProposal() = runTest {
        val savedState = SavedStateHandle()
        val proposal = TaggingResult("Page", "Summary", "", "", emptyList())
        val original = BookmarkAiReviewViewModel(savedState)
        original.generate { Result.success(proposal) }
        assertEquals(AiReviewUiState.Success(proposal), original.state.value)

        val restored = BookmarkAiReviewViewModel(savedState)
        restored.generate { throw AssertionError("Restoring a completed review must not generate again") }
        assertEquals(AiReviewUiState.RestoredAttempt, restored.state.value)
    }

    @Test fun restoredAttemptShowsUnavailableWhenExplicitRetryIsBlocked() = runTest {
        val model = BookmarkAiReviewViewModel(SavedStateHandle(mapOf("attemptStarted" to true)))
        val readiness = AiCoreReadiness.Status(FeatureStatus.DOWNLOADABLE)
        var calls = 0
        val generate: suspend () -> Result<TaggingResult> = {
            calls++
            Result.failure(AiGenerationBlocked(readiness))
        }

        model.generate(proposal = generate)
        assertEquals(0, calls)
        assertEquals(AiReviewUiState.RestoredAttempt, model.state.value)

        model.generate(retry = true, proposal = generate)
        assertEquals(1, calls)
        assertEquals(AiReviewUiState.Unavailable(readiness), model.state.value)
    }

    @Test fun sdkExecutionErrorsSelectTheirReviewMessages() = runTest {
        val cases = mapOf(
            ErrorCode.BUSY to R.string.ai_review_busy,
            ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED to R.string.ai_review_quota,
            ErrorCode.BACKGROUND_USE_BLOCKED to R.string.ai_review_background,
            ErrorCode.UNKNOWN to R.string.ai_review_try_again
        )
        for ((code, message) in cases) {
            val model = BookmarkAiReviewViewModel(SavedStateHandle())
            model.generate { Result.failure(AiCoreExecutionException(code, "SDK error")) }
            assertEquals(AiReviewUiState.Error(message), model.state.value)
        }
    }

    @Test fun onlyPageAcquisitionFailuresOfferBrowserRecovery() = runTest {
        val model = BookmarkAiReviewViewModel(SavedStateHandle())
        model.generate { Result.failure(AiProposalPageException("HTTP error 403")) }
        assertEquals(AiReviewUiState.Error(R.string.ai_review_page_unavailable), model.state.value)

        model.generate(retry = true) { Result.failure(IllegalArgumentException("Invalid proposal")) }
        assertEquals(AiReviewUiState.Error(R.string.ai_review_try_again), model.state.value)
    }
}
