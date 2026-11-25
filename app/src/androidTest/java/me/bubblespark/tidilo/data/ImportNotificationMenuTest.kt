package me.bubblespark.tidilo.data

import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.ImportNotificationEntity
import me.bubblespark.tidilo.ui.screens.notifications.NotificationContent
import me.bubblespark.tidilo.ui.screens.notifications.NotificationListItem
import me.bubblespark.tidilo.ui.screens.notifications.NotificationSections
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Rule
import org.junit.Test

/** Keeps populated Room history coverage after replacing the intrinsic-size popup with a list. */
class ImportNotificationMenuTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun populatedNotificationListDisplaysPersistedItemWithoutIntrinsicMeasurementCrash() {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            runBlocking {
                db.importNotificationDao().insert(
                    ImportNotificationEntity(
                        type = "WARNING",
                        url = "https://regression.invalid/article",
                        domain = "regression.invalid",
                        message = "Already imported bookmark",
                        createdAt = 100
                    )
                )
            }
            composeRule.setContent {
                TidiloTheme(blurEnabled = false, dynamicColor = false) {
                    val history by db.importNotificationDao().observeNotifications().collectAsState(emptyList())
                    NotificationContent(
                        NotificationSections(emptyList(), history.map(NotificationListItem::History)),
                        emptySet(), {}, {}, {}, {}, {}
                    )
                }
            }
            composeRule.waitUntil(5000) {
                composeRule.onAllNodes(androidx.compose.ui.test.hasText("Already imported bookmark"), useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("Already imported bookmark", useUnmergedTree = true).assertIsDisplayed()
            composeRule.onNodeWithText("regression.invalid", useUnmergedTree = true).assertIsDisplayed()
        } finally {
            db.close()
        }
    }
}
