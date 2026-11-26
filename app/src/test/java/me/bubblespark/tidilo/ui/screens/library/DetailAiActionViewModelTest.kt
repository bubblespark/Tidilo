package me.bubblespark.tidilo.ui.screens.library

import com.google.mlkit.genai.common.FeatureStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.bubblespark.tidilo.config.AiAnalysisMode
import me.bubblespark.tidilo.data.AiCoreReadiness
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DetailAiActionViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun disablingAfterReadyPreventsDetailFromOpeningReview() = runTest {
        val model = DetailAiActionViewModel()
        model.check({ AiAnalysisMode.AICORE }) { AiCoreReadiness.Status(FeatureStatus.AVAILABLE) }
        assertEquals(DetailAiState.Ready, model.state.value)
        assertFalse(model.consumeReady(AiAnalysisMode.DISABLE))
        assertEquals(DetailAiState.Disabled, model.state.value)
    }
}
