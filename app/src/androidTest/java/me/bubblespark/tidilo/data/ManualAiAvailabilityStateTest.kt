package me.bubblespark.tidilo.data

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.unit.Density
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.mlkit.genai.common.GenAiException.ErrorCode
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.data.db.BookmarkEntity
import me.bubblespark.tidilo.service.AiCoreExecutionException
import me.bubblespark.tidilo.service.AiGenerationBlocked
import me.bubblespark.tidilo.ui.screens.library.AI_REVIEW_STATUS_ERROR_TAG
import me.bubblespark.tidilo.ui.screens.library.AI_REVIEW_STATUS_INFO_TAG
import me.bubblespark.tidilo.ui.screens.library.AiReviewUiState
import me.bubblespark.tidilo.ui.screens.library.BookmarkAiReviewScreen
import me.bubblespark.tidilo.ui.screens.library.BookmarkAiReviewViewModel
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ManualAiAvailabilityStateTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()
    private val activity get() = composeRule.activity

    /** English at the default font scale, then Japanese at double font scale. */
    private val variants = listOf(Locale.ENGLISH to 1f, Locale.JAPANESE to 2f)

    private fun localized(variant: Pair<Locale, Float>): Context = activity.createConfigurationContext(
        Configuration(activity.resources.configuration).apply {
            setLocale(variant.first)
            fontScale = variant.second
        }
    )

    @Composable
    private fun Localized(context: Context, content: @Composable () -> Unit) {
        val configuration = context.resources.configuration
        CompositionLocalProvider(
            LocalContext provides context, LocalConfiguration provides configuration,
            LocalResources provides context.resources,
            LocalDensity provides Density(LocalDensity.current.density, configuration.fontScale)
        ) {
            TidiloTheme(darkTheme = false, dynamicColor = false, blurEnabled = false, content = content)
        }
    }

    private fun nodes(matcher: SemanticsMatcher): List<SemanticsNode> =
        composeRule.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes()

    private fun await(matcher: SemanticsMatcher): SemanticsNode {
        composeRule.waitUntil(8_000) { nodes(matcher).isNotEmpty() }
        return nodes(matcher).first()
    }

    private fun click(node: SemanticsNode) {
        val click = generateSequence(node) { it.parent }.firstNotNullOf { it.config.getOrNull(SemanticsActions.OnClick)?.action }
        composeRule.runOnUiThread { assertTrue(click()) }
        composeRule.waitForIdle()
    }

    private fun seedReviewModel(id: String, savedState: SavedStateHandle) = composeRule.runOnUiThread {
        val factory = viewModelFactory { initializer { BookmarkAiReviewViewModel(savedState) } }
        ViewModelProvider(activity, factory)["ai-review-$id", BookmarkAiReviewViewModel::class.java]
    }

    /** Runs [block] with one bookmark in an in-memory Library and offline visual caches. */
    private fun withReviewFixture(block: suspend (InMemoryLibrary, String) -> Unit): Unit = runBlocking {
        val id = "review-fixture"
        val caches = OfflineVisualCaches()
        val library = withContext(Dispatchers.IO) { InMemoryLibrary(activity.applicationContext).also { it.awaitReady() } }
        try {
            withContext(Dispatchers.IO) {
                library.db.bookmarkDao().insertOrUpdate(BookmarkEntity(id, "Review fixture", "https://example.invalid/$id", "",
                    createdAt = 1, updatedAt = 1))
            }
            block(library, id)
        } finally {
            caches.close()
            library.close()
        }
    }

    @Test fun blockedReviewRestoresWithRetryAndManualRecovery() = withReviewFixture { library, id ->
        val savedState = SavedStateHandle()
        composeRule.runOnUiThread {
            val original = BookmarkAiReviewViewModel(savedState)
            original.generate { Result.failure(AiGenerationBlocked(null)) }
            assertEquals(AiReviewUiState.Unavailable(null), original.state.value)
        }
        seedReviewModel(id, savedState)
        val model = library.viewModel()
        var variant by mutableStateOf(variants.first())
        var returnedToDetail = false
        composeRule.setContent {
            Localized(remember(variant) { localized(variant) }) {
                Surface {
                    BookmarkAiReviewScreen(
                        id, model, onNavigateBack = { returnedToDetail = true },
                        generateProposal = { _, _, _ ->
                            throw AssertionError("Restoring the screen must not generate automatically")
                        }
                    )
                }
            }
        }
        for (target in variants) {
            composeRule.runOnUiThread { variant = target }
            val context = localized(target)
            for (resource in listOf(
                R.string.ai_review_restored_title, R.string.ai_review_restored_message, R.string.common_retry,
                R.string.ai_review_back_to_detail, R.string.detail_ai_manual_fallback
            )) {
                await(hasText(context.getString(resource)))
            }
            for (tag in listOf(AI_REVIEW_STATUS_INFO_TAG, AI_REVIEW_STATUS_ERROR_TAG)) {
                composeRule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).assertCountEquals(0)
            }
        }
        click(await(hasText(localized(variants.last()).getString(R.string.ai_review_back_to_detail))))
        assertTrue(returnedToDetail)
    }

    @Test fun unavailableRetryShowsLocalizedManualRecoveryWithInfoTint(): Unit =
        assertLocalizedReviewRetryRendering(blocked = true)

    @Test fun errorRetryShowsLocalizedRetryWithErrorTint(): Unit =
        assertLocalizedReviewRetryRendering(blocked = false)

    private fun assertLocalizedReviewRetryRendering(blocked: Boolean) = withReviewFixture { library, id ->
        seedReviewModel(id, SavedStateHandle(mapOf("attemptStarted" to true)))
        val model = library.viewModel()
        var variant by mutableStateOf(variants.first())
        var calls = 0
        var returnedToDetail = false
        lateinit var colors: ColorScheme
        composeRule.setContent {
            Localized(remember(variant) { localized(variant) }) {
                val scheme = MaterialTheme.colorScheme
                SideEffect { colors = scheme }
                Surface {
                    BookmarkAiReviewScreen(
                        id, model, onNavigateBack = { returnedToDetail = true },
                        generateProposal = { _, _, _ ->
                            calls++
                            val failure = if (blocked) AiGenerationBlocked(null)
                            else AiCoreExecutionException(ErrorCode.BUSY, "SDK busy")
                            Result.failure(failure)
                        }
                    )
                }
            }
        }

        var context = localized(variants.first())
        await(hasText(context.getString(R.string.ai_review_restored_title)))
        val retry = await(hasText(context.getString(R.string.common_retry)))
        assertEquals(0, calls)
        click(retry)

        val title = if (blocked) R.string.ai_review_unavailable_title else R.string.ai_review_error_title
        val reason = if (blocked) R.string.ai_review_disabled_message else R.string.ai_review_busy
        val iconTag = if (blocked) AI_REVIEW_STATUS_INFO_TAG else AI_REVIEW_STATUS_ERROR_TAG
        val absent = if (blocked) {
            listOf(R.string.ai_analysis_off_message, R.string.ai_analysis_open_settings, R.string.ai_review_error_title)
        } else {
            listOf(R.string.ai_review_unavailable_title, R.string.ai_review_back_to_detail, R.string.detail_ai_manual_fallback)
        }
        for (target in variants) {
            composeRule.runOnUiThread { variant = target }
            context = localized(target)
            await(hasText(context.getString(title)))
            await(hasText(context.getString(reason)))
            await(hasText(context.getString(if (blocked) R.string.ai_review_back_to_detail else R.string.common_retry)))
            if (blocked) await(hasText(context.getString(R.string.detail_ai_manual_fallback)))
            for (resource in absent) {
                composeRule.onAllNodes(hasText(context.getString(resource)), useUnmergedTree = true).assertCountEquals(0)
            }
            val tint = (if (blocked) colors.onSurfaceVariant else colors.error).toArgb()
            assertRenderedTint(hasTestTag(iconTag), hasText(context.getString(reason)), tint, colors.surface.toArgb(),
                "${target.first.language} ${if (blocked) "unavailable" else "error"}")
        }
        assertEquals(1, calls)

        click(await(hasText(context.getString(if (blocked) R.string.ai_review_back_to_detail else R.string.common_retry))))
        if (blocked) {
            assertTrue(returnedToDetail)
        } else {
            assertFalse(returnedToDetail)
            assertEquals(2, calls)
        }
    }

    /** Asserts that most foreground pixels of the icon and the reason text use [tint]. */
    private fun assertRenderedTint(icon: SemanticsMatcher, message: SemanticsMatcher, tint: Int, background: Int, label: String) {
        composeRule.waitForIdle()
        // This fixture paints a flat Surface. Production TidiloBackground may draw gradients
        // behind the review, where a single background color would misclassify pixels.
        fun tintCoverage(matcher: SemanticsMatcher): Pair<Int, Int> {
            val bitmap = composeRule.onAllNodes(matcher, useUnmergedTree = true).onFirst().captureToImage().asAndroidBitmap()
            try {
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

                fun matches(pixel: Int, expected: Int): Boolean =
                    abs(Color.red(pixel) - Color.red(expected)) <= 8 &&
                        abs(Color.green(pixel) - Color.green(expected)) <= 8 &&
                        abs(Color.blue(pixel) - Color.blue(expected)) <= 8

                var foreground = 0
                var matching = 0
                for (pixel in pixels) {
                    if (Color.alpha(pixel) != 255 || matches(pixel, background)) continue
                    foreground++
                    if (matches(pixel, tint)) matching++
                }
                return matching to foreground
            } finally {
                bitmap.recycle()
            }
        }

        val (iconMatches, iconForeground) = tintCoverage(icon)
        val (textMatches, textForeground) = tintCoverage(message)
        // Allow antialiased edges while rejecting a few incidental matching pixels.
        assertTrue(
            "$label tint coverage: icon $iconMatches/$iconForeground, reason $textMatches/$textForeground",
            iconMatches >= 16 && iconMatches * 4 >= iconForeground &&
                textMatches >= 16 && textMatches * 4 >= textForeground
        )
    }
}
