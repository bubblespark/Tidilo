package me.bubblespark.tidilo.data

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import java.util.concurrent.atomic.AtomicInteger
import me.bubblespark.tidilo.ui.screens.settings.SettingsScreen
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Settings › Data Management › Reset rediscovery history. */
class SettingsDiscoverResetTest {
    @get:Rule val composeRule = createComposeRule()

    private fun dialogButton(label: String) =
        composeRule.onNode(hasText(label) and hasClickAction() and hasAnyAncestor(isDialog()))

    @Test fun resetEntryAsksFirst() {
        val resets = AtomicInteger()
        composeRule.setContent {
            TidiloTheme(blurEnabled = false, dynamicColor = false) {
                SettingsScreen({}, {}, onResetDiscoverHistory = { resets.incrementAndGet() })
            }
        }
        composeRule.onNodeWithText("Data Management").assertExists()
        val row = composeRule.onNodeWithTag("discover_reset_history")
        row.performScrollTo().performClick()
        val title = composeRule.onNodeWithText("Reset rediscovery history?")
        title.assertExists()
        dialogButton("Cancel").performClick()
        title.assertDoesNotExist()
        assertEquals(0, resets.get())

        row.performClick()
        dialogButton("Reset").performClick()
        composeRule.waitUntil(3_000) { resets.get() == 1 }
        assertEquals(1, resets.get())
    }
}
