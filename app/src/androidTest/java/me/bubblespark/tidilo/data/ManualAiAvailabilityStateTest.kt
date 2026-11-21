package me.bubblespark.tidilo.data

import android.content.res.Configuration
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException.ErrorCode
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
import me.bubblespark.tidilo.service.AiCoreExecutionException
import me.bubblespark.tidilo.service.AiGenerationBlocked
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

class ManualAiAvailabilityStateTest {
    @Test fun blockedReviewRestoresWithRetryAndManualRecovery(): Unit = runBlocking {
        val host = HeaderTestHost()
        val dao = AppDatabase.getInstance(host.context).bookmarkDao()
        val id = "restored-review-${UUID.randomUUID()}"
        dao.insertOrUpdate(BookmarkEntity(id, "Review fixture", "https://example.invalid/$id", "",
            createdAt = 1, updatedAt = 1))
        val activity = host.launch()
        try {
            host.main {
                val savedState = SavedStateHandle()
                val original = BookmarkAiReviewViewModel(savedState)
                original.generate { Result.failure(AiGenerationBlocked(null)) }
                assertEquals(AiReviewUiState.Unavailable(null), original.state.value)
                val factory = viewModelFactory {
                    initializer { BookmarkAiReviewViewModel(savedState) }
                }
                ViewModelProvider(activity, factory)["ai-review-$id", BookmarkAiReviewViewModel::class.java]
            }
            for ((locale, dark) in listOf(Locale.ENGLISH to false, Locale.JAPANESE to true)) {
                val configuration = Configuration(activity.resources.configuration).apply {
                    setLocale(locale)
                    fontScale = if (dark) 2f else 1f
                }
                val localized = activity.createConfigurationContext(configuration)
                var returnedToDetail = false
                host.main {
                    val library = ViewModelProvider(activity)[LibraryViewModel::class.java]
                    activity.setContent {
                        CompositionLocalProvider(
                            LocalContext provides localized, LocalConfiguration provides configuration,
                            LocalResources provides localized.resources,
                            LocalDensity provides Density(LocalDensity.current.density, configuration.fontScale)
                        ) {
                            TidiloTheme(darkTheme = dark, dynamicColor = false) {
                                Surface {
                                    BookmarkAiReviewScreen(
                                        id, library, onNavigateBack = { returnedToDetail = true },
                                        generateProposal = { _, _, _ ->
                                            throw AssertionError("Restoring the screen must not generate automatically")
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
                for (resource in listOf(R.string.ai_review_restored_title, R.string.ai_review_restored_message, R.string.common_retry)) {
                    host.await(activity) { host.text(it, localized.getString(resource)) }
                }
                val backLabel = localized.getString(R.string.ai_review_back_to_detail)
                val back = host.await(activity) { host.text(it, backLabel) }
                host.await(activity) { host.text(it, localized.getString(R.string.detail_ai_manual_fallback)) }
                host.capture("263-review-restored-${locale.language}")
                host.main {
                    val click = generateSequence(back) { it.parent }
                        .firstNotNullOf { it.config.getOrNull(SemanticsActions.OnClick)?.action }
                    assertTrue(click())
                    assertTrue(returnedToDetail)
                }
            }
        } finally {
            host.main { activity.finish() }
            dao.deleteById(id)
        }
    }

    @Test fun disabledRetryShowsLocalizedManualRecovery(): Unit = runBlocking {
        val host = HeaderTestHost()
        val dao = AppDatabase.getInstance(host.context).bookmarkDao()
        for ((locale, dark) in listOf(Locale.ENGLISH to false, Locale.JAPANESE to true)) {
            val id = "disabled-review-${UUID.randomUUID()}"
            dao.insertOrUpdate(BookmarkEntity(id, "Review fixture", "https://example.invalid/$id", "",
                createdAt = 1, updatedAt = 1))
            try {
                val activity = host.launch()
                try {
                    val configuration = Configuration(activity.resources.configuration).apply {
                        setLocale(locale)
                        fontScale = if (dark) 2f else 1f
                    }
                    val localized = activity.createConfigurationContext(configuration)
                    var calls = 0
                    var returnedToDetail = false
                    host.main {
                        val factory = viewModelFactory {
                            initializer { BookmarkAiReviewViewModel(SavedStateHandle(mapOf("attemptStarted" to true))) }
                        }
                        ViewModelProvider(activity, factory)["ai-review-$id", BookmarkAiReviewViewModel::class.java]
                        val library = ViewModelProvider(activity)[LibraryViewModel::class.java]
                        activity.setContent {
                            CompositionLocalProvider(
                                LocalContext provides localized, LocalConfiguration provides configuration,
                                LocalResources provides localized.resources,
                                LocalDensity provides Density(LocalDensity.current.density, configuration.fontScale)
                            ) {
                                TidiloTheme(darkTheme = dark, dynamicColor = false) {
                                    Surface {
                                        BookmarkAiReviewScreen(
                                            id, library, onNavigateBack = { returnedToDetail = true },
                                            generateProposal = { _, _, _ ->
                                                calls++
                                                Result.failure(AiGenerationBlocked(null))
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    suspend fun awaitText(resource: Int) =
                        host.await(activity) { host.text(it, localized.getString(resource)) }

                    awaitText(R.string.ai_review_restored_title)
                    val retry = awaitText(R.string.common_retry)
                    host.main {
                        assertEquals(0, calls)
                        assertTrue(generateSequence(retry) { it.parent }
                            .firstNotNullOf { it.config.getOrNull(SemanticsActions.OnClick)?.action }.invoke())
                    }

                    awaitText(R.string.ai_review_unavailable_title)
                    awaitText(R.string.ai_review_disabled_message)
                    awaitText(R.string.detail_ai_manual_fallback)
                    val back = awaitText(R.string.ai_review_back_to_detail)
                    host.capture("263-r26-review-disabled-${locale.language}")
                    host.main {
                        assertEquals(1, calls)
                        val absent = listOf(
                            R.string.ai_analysis_off_message, R.string.ai_analysis_open_settings, R.string.ai_review_error_title
                        )
                        assertTrue(host.nodes(activity).none { node ->
                            absent.any { host.text(node, localized.getString(it)) }
                        })
                        assertTrue(generateSequence(back) { it.parent }
                            .firstNotNullOf { it.config.getOrNull(SemanticsActions.OnClick)?.action }.invoke())
                        assertTrue(returnedToDetail)
                    }
                } finally {
                    host.main { activity.finish() }
                }
            } finally {
                dao.deleteById(id)
            }
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

    @Test fun restoredAttemptShowsUnavailableWhenExplicitRetryIsBlocked(): Unit = runBlocking {
        withContext(Dispatchers.Main) {
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
    }

    @Test fun sdkExecutionErrorsSelectTheirReviewMessages(): Unit = runBlocking {
        withContext(Dispatchers.Main) {
            val cases = mapOf(
                ErrorCode.BUSY to R.string.ai_review_busy,
                ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED to R.string.ai_review_quota,
                ErrorCode.BACKGROUND_USE_BLOCKED to R.string.ai_review_background
            )
            for ((code, message) in cases) {
                val model = BookmarkAiReviewViewModel(SavedStateHandle())
                model.generate { Result.failure(AiCoreExecutionException(code, "SDK error")) }
                assertEquals(AiReviewUiState.Error(message), model.state.value)
            }
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
