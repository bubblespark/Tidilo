package me.bubblespark.tidilo.data

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import me.bubblespark.tidilo.ui.components.OverlayBlurController
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The entity picker across one Activity recreation; the Library ViewModel lives outside the Activity. */
class EntityPickerRecreationTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test fun pickerOpenAcrossRecreationKeepsDraftsAndParentOwnership() = withEntityPickerLibrary(composeRule) { library, detailId ->
        val picker = EntityPickerDriver(composeRule)
        val model = library.viewModel()
        fun render() = composeRule.activityRule.scenario.onActivity { activity ->
            activity.setContent { EntityPickerDetailContent(detailId, model) { picker.controller = it } }
        }
        render()
        picker.addDrafts()
        val parentToken = picker.parentToken()
        picker.open()
        picker.awaitCandidateList()

        val previous: OverlayBlurController? = picker.controller
        composeRule.activityRule.scenario.recreate()
        render()
        composeRule.waitUntil(10_000) {
            var restored = false
            composeRule.runOnUiThread { restored = picker.controller !== previous && picker.controller?.activeCount == 2 }
            restored
        }
        picker.awaitCandidateList()
        picker.awaitSelected(2)
        composeRule.runOnUiThread {
            assertTrue(picker.requireController().registry.lowerLayers(Long.MAX_VALUE).contains(parentToken))
        }

        picker.pressSystemBack()
        picker.assertClosedWithDrafts(parentToken)
    }
}
