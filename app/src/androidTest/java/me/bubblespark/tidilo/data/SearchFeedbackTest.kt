package me.bubblespark.tidilo.data

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.ui.screens.library.PagingFeedback
import me.bubblespark.tidilo.ui.screens.library.SearchResultFeedback
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.roundToInt

/** Feedback stays centered below the body input, excluding scaffold clearances. */
class SearchFeedbackTest {
    @Test fun loadingAndRetryCenterInRemainingViewport(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        val viewport = mutableStateOf(600)
        val input = mutableStateOf(56)
        val spacing = mutableStateOf(16)
        val error = mutableStateOf(false)
        var indicator = Rect.Zero
        var retries = 0
        val density = host.context.resources.displayMetrics.density
        try {
            host.main {
                activity.setContent { MaterialTheme {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(1), modifier = Modifier.size(320.dp, viewport.value.dp),
                        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 104.dp, bottom = 80.dp),
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(spacing.value.dp)
                    ) {
                        item(key = "input") { Box(Modifier.height(input.value.dp)) }
                        item(key = "feedback") {
                            SearchResultFeedback(
                                viewportHeight = viewport.value.dp,
                                contentPadding = PaddingValues(top = 104.dp, bottom = 80.dp),
                                searchInputHeight = input.value.dp,
                                itemSpacing = spacing.value.dp
                            ) {
                                val measured = Modifier.onGloballyPositioned { indicator = it.boundsInRoot() }
                                if (error.value) PagingFeedback(LoadState.Error(IllegalStateException("Fixture")), { retries++ }, measured)
                                else CircularProgressIndicator(measured)
                            }
                        }
                    }
                } }
            }
            for ((height, inputHeight, gap) in listOf(Triple(600, 56, 16), Triple(420, 84, 10))) {
                for (isError in listOf(false, true)) {
                    host.main { viewport.value = height; input.value = inputHeight; spacing.value = gap; error.value = isError }
                    delay(250)
                    host.main {
                        assertEquals(160f * density, indicator.center.x, 1.1f)
                        val top = (104 * density).roundToInt() + (inputHeight * density).roundToInt() + (gap * density).roundToInt()
                        val bottom = (height * density).roundToInt() - (80 * density).roundToInt()
                        assertEquals((top + bottom) / 2f, indicator.center.y, 1.1f)
                        assertTrue(indicator.top >= top)
                        assertTrue(indicator.bottom <= bottom + 1f)
                        if (!isError) assertEquals(40f * density, indicator.width, 1.1f)
                    }
                    if (isError) {
                        val retry = host.await(activity) { host.text(it, host.context.getString(R.string.common_retry)) }
                        host.main {
                            var node = retry
                            while (node.config.getOrNull(SemanticsActions.OnClick) == null) node = requireNotNull(node.parent)
                            assertTrue(node.config[SemanticsActions.OnClick].action?.invoke() == true)
                        }
                    }
                }
            }
            assertEquals(2, retries)
        } finally { host.main { activity.finish() } }
    }
}
