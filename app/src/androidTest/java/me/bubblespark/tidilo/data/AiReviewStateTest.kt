package me.bubblespark.tidilo.data

import android.content.res.Configuration
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.mlkit.genai.common.FeatureStatus
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.config.AiAnalysisMode
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.service.AiProposalPageException
import me.bubblespark.tidilo.service.TaggingResult
import me.bubblespark.tidilo.ui.screens.library.AiReviewUiState
import me.bubblespark.tidilo.ui.screens.library.BookmarkAiReviewScreen
import me.bubblespark.tidilo.ui.screens.library.BookmarkAiReviewViewModel
import me.bubblespark.tidilo.ui.screens.library.DetailAiActionViewModel
import me.bubblespark.tidilo.ui.screens.library.DetailAiState
import me.bubblespark.tidilo.ui.screens.library.LibraryViewModel
import me.bubblespark.tidilo.ui.screens.settings.AiModelSetupViewModel
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.*
import org.junit.Test

class AiReviewStateTest {
    @Test fun restoredReviewShowsLocalizedRecoveryAndRetry(): Unit = runBlocking {
        val host = HeaderTestHost()
        val dao = AppDatabase.getInstance(host.context).bookmarkDao()
        val id = "restored-review-${UUID.randomUUID()}"
        dao.insertOrUpdate(BookmarkEntity(id, "Review fixture", "https://example.invalid/$id", "",
            createdAt = 1, updatedAt = 1))
        val activity = host.launch()
        try {
            for ((locale, dark) in listOf(Locale.ENGLISH to false, Locale.JAPANESE to true)) {
                val configuration = Configuration(activity.resources.configuration).apply { setLocale(locale) }
                val localized = activity.createConfigurationContext(configuration)
                host.main {
                    val factory = viewModelFactory {
                        initializer { BookmarkAiReviewViewModel(SavedStateHandle(mapOf("attemptStarted" to true))) }
                    }
                    ViewModelProvider(activity, factory)["ai-review-$id", BookmarkAiReviewViewModel::class.java]
                    val library = ViewModelProvider(activity)[LibraryViewModel::class.java]
                    activity.setContent {
                        CompositionLocalProvider(
                            LocalContext provides localized, LocalConfiguration provides configuration,
                            LocalResources provides localized.resources
                        ) {
                            TidiloTheme(darkTheme = dark, dynamicColor = false) {
                                Surface {
                                    BookmarkAiReviewScreen(id, library, {}, generateProposal = { _, _, _ ->
                                        throw AssertionError("Restoring the screen must not generate automatically")
                                    })
                                }
                            }
                        }
                    }
                }
                for (resource in listOf(R.string.ai_review_restored_title, R.string.ai_review_restored_message, R.string.common_retry)) {
                    host.await(activity) { host.text(it, localized.getString(resource)) }
                }
                host.capture("263-review-restored-${locale.language}")
            }
        } finally {
            host.main { activity.finish() }
            dao.deleteById(id)
        }
    }

    @Test fun restoredAttemptWaitsForAnExplicitRetry(): Unit = runBlocking {
        withContext(Dispatchers.Main) {
            val model = BookmarkAiReviewViewModel(SavedStateHandle(mapOf("attemptStarted" to true)))
            val proposal = TaggingResult("Page", "Summary", "", "", emptyList())

            model.generate { throw AssertionError("Restoring review must not start generation") }
            assertEquals(AiReviewUiState.RestoredAttempt, model.state.value)

            model.generate(retry = true) { Result.success(proposal) }
            assertEquals(AiReviewUiState.Success(proposal), model.state.value)
        }
    }

    @Test fun completedAttemptRestoresWithoutStartingAnotherProposal(): Unit = runBlocking {
        withContext(Dispatchers.Main) {
            val savedState = SavedStateHandle()
            val proposal = TaggingResult("Page", "Summary", "", "", emptyList())
            val original = BookmarkAiReviewViewModel(savedState)
            original.generate { Result.success(proposal) }
            assertEquals(AiReviewUiState.Success(proposal), original.state.value)

            val restored = BookmarkAiReviewViewModel(savedState)
            restored.generate { throw AssertionError("Restoring a completed review must not generate again") }
            assertEquals(AiReviewUiState.RestoredAttempt, restored.state.value)
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
