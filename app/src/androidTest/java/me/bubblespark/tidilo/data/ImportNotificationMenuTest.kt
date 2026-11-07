package me.bubblespark.tidilo.data

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.ImportNotificationEntity
import me.bubblespark.tidilo.ui.screens.notifications.NotificationContent
import me.bubblespark.tidilo.ui.screens.notifications.NotificationListItem
import org.junit.Test

/** Keeps populated Room history coverage after replacing the intrinsic-size popup with a list. */
class ImportNotificationMenuTest {
    @Test fun populatedNotificationListDisplaysPersistedItemWithoutIntrinsicMeasurementCrash(): Unit = runBlocking {
        val host = HeaderTestHost()
        val db = Room.inMemoryDatabaseBuilder(host.context, AppDatabase::class.java).build()
        val activity = host.launch()
        try {
            db.importNotificationDao().insert(ImportNotificationEntity(type = "WARNING",
                url = "https://regression.invalid/article", domain = "regression.invalid",
                message = "Already imported bookmark", createdAt = 100))
            host.main { activity.setContent { MaterialTheme {
                val history by db.importNotificationDao().observeNotifications().collectAsState(emptyList())
                NotificationContent(history.map(NotificationListItem::History), history.isNotEmpty(), emptySet(),
                    {}, {}, {}, {}, {})
            } } }
            host.await(activity) { host.text(it, "Already imported bookmark") }
            host.await(activity) { host.text(it, "regression.invalid") }
        } finally { host.main { activity.finish() }; db.close() }
    }
}
