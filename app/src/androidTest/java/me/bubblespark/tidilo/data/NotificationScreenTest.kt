package me.bubblespark.tidilo.data

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import me.bubblespark.tidilo.data.db.RssFeedEntity
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.ui.screens.notifications.NotificationContent
import me.bubblespark.tidilo.ui.screens.notifications.NotificationListItem
import org.junit.Assert.*
import org.junit.Test

class NotificationScreenTest {
    @Test fun historyExposesButtonRoleAndOpensSelectedNotification(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        val row = NotificationListItem.History(me.bubblespark.tidilo.data.db.ImportNotificationEntity(
            id = 42, type = "INFO", domain = "History fixture", url = "https://example.invalid", message = "Saved"))
        var opened: String? = null
        try {
            host.main { activity.setContent { MaterialTheme {
                NotificationContent(listOf(row), false, emptySet(), {}, {}, {}, {}, { opened = it.key })
            } } }
            val label = host.await(activity) { host.text(it, "History fixture") }
            host.main {
                val button = generateSequence(label) { it.parent }.first {
                    it.config.getOrNull(SemanticsActions.OnClick) != null
                }
                assertEquals(androidx.compose.ui.semantics.Role.Button, button.config.getOrNull(SemanticsProperties.Role))
                assertTrue(button.config[SemanticsActions.OnClick].action!!.invoke())
                assertEquals(row.key, opened)
            }
        } finally { host.main { activity.finish() } }
    }

    @Test fun newerFailureClosesAnAlreadyOpenActionMenu(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        val row = mutableStateOf(NotificationListItem.RssXmlFailure(RssFeedEntity("feed", "https://xml.invalid/feed",
            "XML", error = "HTTP 503", lastAttemptAt = 100)))
        try {
            host.main { activity.setContent { MaterialTheme {
                NotificationContent(listOf(row.value), false, emptySet(), {}, {}, {}, {}, {})
            } } }
            val menu = host.await(activity) { it.config.getOrNull(SemanticsProperties.TestTag) == "notification_actions:feed:feed" }
            host.main { menu.config[SemanticsActions.OnClick].action?.invoke() }
            host.await(activity) { host.text(it, host.context.getString(R.string.common_delete)) }
            host.main { row.value = NotificationListItem.RssXmlFailure(row.value.feed.copy(error = "HTTP 504", lastAttemptAt = 200)) }
            host.await(activity) { host.text(it, "HTTP 504") }
            delay(400)
            host.main { assertFalse("An old error menu must not target a replacement failure",
                host.nodes(activity).any { host.text(it, host.context.getString(R.string.common_delete)) }) }
        } finally { host.main { activity.finish() } }
    }

    @Test fun errorOverflowDisplaysDateAndDeletesSelectedTask(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        var deleted: String? = null
        val row = NotificationListItem.Failure(ImportTask("selected", "https://example.invalid/page", "Page",
            TaskStatus.ERROR, "Failed", errorMessage = "HTTP 503", createdAt = 0))
        try {
            host.main { activity.setContent { MaterialTheme {
                NotificationContent(listOf(row), false, emptySet(), {}, {}, {}, { deleted = it.key }, {})
            } } }
            val menu = host.await(activity) { it.config.getOrNull(SemanticsProperties.TestTag) == "notification_actions:task:selected" }
            host.main {
                assertTrue(host.nodes(activity).any { node -> node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.contains("1970/01/01") && it.text.endsWith(":00") } == true })
                assertTrue(menu.config[SemanticsActions.OnClick].action?.invoke() == true)
            }
            val delete = host.await(activity) { host.text(it, host.context.getString(R.string.common_delete)) }
            host.main {
                var target = delete
                while (target.config.getOrNull(SemanticsActions.OnClick) == null) target = requireNotNull(target.parent)
                target.config[SemanticsActions.OnClick].action?.invoke()
                assertEquals("task:selected", deleted)
            }
        } finally { host.main { activity.finish() } }
    }
}
