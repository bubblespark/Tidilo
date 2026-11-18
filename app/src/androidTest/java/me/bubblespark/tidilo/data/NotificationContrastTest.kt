package me.bubblespark.tidilo.data

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.toSize
import androidx.room.withTransaction
import androidx.test.core.app.ActivityScenario
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.MainActivity
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.config.AiAnalysisMode
import me.bubblespark.tidilo.config.AppConfig
import me.bubblespark.tidilo.config.AppTheme
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.ImportNotificationEntity
import me.bubblespark.tidilo.data.db.ImportTaskEntity
import me.bubblespark.tidilo.data.db.RssFeedEntity
import me.bubblespark.tidilo.data.notifications.NotificationCategory
import me.bubblespark.tidilo.data.notifications.NotificationCoordinator
import me.bubblespark.tidilo.ui.theme.ModernDarkColorScheme
import me.bubblespark.tidilo.ui.theme.ModernLightColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class NotificationContrastTest {
    /** Uses a dedicated emulator because opening the production route marks unread history as read. */
    @Test
    fun productionNotificationRouteHasReadableTitlesAndActionsAcrossThemeChanges(): Unit = runBlocking {
        val host = HeaderTestHost()
        val db = AppDatabase.getInstance(host.context)
        val notifications = db.importNotificationDao()
        val originalTheme = AppConfig.currentTheme.value
        val originalAiMode = AppConfig.aiAnalysisMode.value
        val originalNightMode = nightMode(host).substringAfter("Night mode: ")
        check(originalNightMode in setOf("yes", "no", "auto", "custom_schedule", "custom_bedtime")) {
            "Cannot restore the current night mode: $originalNightMode"
        }
        val key = UUID.randomUUID().toString()
        val historyTitle = "history-261.invalid"
        val importTitle = "Import contrast fixture"
        val rssTitle = "RSS contrast fixture"
        val importAction = "notification_actions:task:$key"
        val rssAction = "notification_actions:feed:$key"
        val titles = listOf(historyTitle, importTitle, rssTitle)
        val actions = listOf(importAction, rssAction)
        val createdAt = System.currentTimeMillis()
        var historyId = 0L

        assertEquals("Use a test profile with no unread notification history", 0, notifications.observeUnreadCount().first())
        try {
            AppConfig.setAiAnalysisMode(AiAnalysisMode.DISABLE)
            AppConfig.setTheme(AppTheme.DEFAULT)
            nightMode(host, "yes")
            historyId = db.withTransaction {
                db.importTaskDao().insertOrUpdate(ImportTaskEntity(
                    key, "https://import-261.invalid/$key", importTitle, TaskStatus.ERROR.name,
                    "Failed", errorMessage = "HTTP 503", createdAt = createdAt - 1000
                ))
                db.rssDao().putFeed(RssFeedEntity(
                    key, "https://rss-261.invalid/$key", rssTitle, enabled = false,
                    error = "HTTP 503", lastAttemptAt = createdAt - 2000
                ))
                notifications.insert(ImportNotificationEntity(
                    type = "INFO", url = "https://$historyTitle/$key", domain = historyTitle,
                    message = "Saved", createdAt = createdAt
                ))
            }
            assertEquals(1, notifications.observeUnreadCount().first())
            launchNotifications(host).use { scenario ->
                verifyAppearance(host, scenario, titles, actions, AppTheme.DEFAULT, dark = true)
                withTimeout(5000) { notifications.observeUnreadCount().first { it == 0 } }

                nightMode(host, "no")
                verifyAppearance(host, scenario, titles, actions, AppTheme.DEFAULT, dark = false)
                AppConfig.setTheme(AppTheme.MODERN)
                verifyAppearance(host, scenario, titles, actions, AppTheme.MODERN, dark = false)
                nightMode(host, "yes")
                verifyAppearance(host, scenario, titles, actions, AppTheme.MODERN, dark = true)
            }
            assertEquals(0, notifications.observeUnreadCount().first())
            launchNotifications(host).use { scenario ->
                verifyAppearance(host, scenario, titles, actions, AppTheme.MODERN, dark = true)
                click(host, awaitNode(host, scenario) { it.tag() == importAction })
                val delete = host.context.getString(R.string.common_delete)
                click(host, awaitNode(host, scenario) { host.text(it, delete) })
                withTimeout(5000) {
                    while (db.importTaskDao().getTaskById(key) != null) delay(30)
                }
            }
        } finally {
            AppConfig.setTheme(originalTheme)
            AppConfig.setAiAnalysisMode(originalAiMode)
            nightMode(host, originalNightMode)
            if (historyId != 0L) db.withTransaction {
                notifications.deleteById(historyId)
                db.importTaskDao().deleteById(key)
                db.openHelper.writableDatabase.execSQL("DELETE FROM rss_feeds WHERE id = ?", arrayOf(key))
            }
        }
    }

    private fun launchNotifications(host: HeaderTestHost): ActivityScenario<MainActivity> =
        ActivityScenario.launch(Intent(host.context, MainActivity::class.java)
            .putExtra(NotificationCoordinator.EXTRA_DESTINATION, NotificationCategory.IMPORT_ERROR.destination))

    private suspend fun verifyAppearance(
        host: HeaderTestHost,
        scenario: ActivityScenario<MainActivity>,
        titles: List<String>,
        actions: List<String>,
        theme: AppTheme,
        dark: Boolean
    ) {
        val expectedCanvas = when (theme) {
            AppTheme.DEFAULT -> if (dark) dynamicDarkColorScheme(host.context).background
                else dynamicLightColorScheme(host.context).background
            AppTheme.MODERN -> if (dark) ModernDarkColorScheme.background else ModernLightColorScheme.background
        }
        val appearance = "$theme ${if (dark) "dark" else "light"}"
        val firstTitle = awaitNode(host, scenario) { host.text(it, titles.first()) }
        val canvasY = onMain(host) { firstTitle.screenBounds().center.y }
        withTimeout(6000) {
            var matches = false
            while (!matches) {
                screenshot(host) { matches = it.colorAt(2f, canvasY) == expectedCanvas }
                if (!matches) delay(30)
            }
        }

        val titleNodes = titles.map { title -> awaitNode(host, scenario) { host.text(it, title) } }
        val actionNodes = actions.map { tag -> awaitNode(host, scenario) { it.tag() == tag } }
        val (titleSamples, actionBounds) = onMain(host) {
            val titlesOnScreen = titleNodes.map { it.textColor() to it.screenBounds() }
            val actionsOnScreen = actionNodes.map {
                assertNull(it.config.getOrNull(SemanticsProperties.Disabled))
                it.screenBounds()
            }
            titlesOnScreen to actionsOnScreen
        }
        screenshot(host) { image ->
            titles.zip(titleSamples).forEach { (title, sample) ->
                val (color, bounds) = sample
                // Sample the blank margin beside the row.
                assertContrast("$appearance $title", color, image.colorAt(2f, bounds.center.y), 4.5)
            }
            actions.zip(actionBounds).forEach { (tag, bounds) ->
                val foreground = image.colorAt(bounds.center.x, bounds.center.y)
                val background = image.colorAt(bounds.left + bounds.width * 0.1f, bounds.center.y)
                assertContrast("$appearance $tag", foreground, background, 3.0)
            }
        }

        val menuLabels = listOf(R.string.common_retry, R.string.common_delete).map(host.context::getString)
        actionNodes.forEach { action ->
            click(host, action)
            val menuNodes = menuLabels.map { label -> awaitNode(host, scenario) { host.text(it, label) } }
            // Wait for the popup's entrance scale and opacity to settle before taking screen coordinates.
            delay(250)
            val menuSamples = onMain(host) {
                menuNodes.map { label ->
                    val item = label.clickOwner()
                    assertNull(item.config.getOrNull(SemanticsProperties.Disabled))
                    label.screenBounds() to item.screenBounds()
                }
            }
            screenshot(host) { image ->
                menuLabels.zip(menuSamples).forEach { (label, sample) ->
                    val (textBounds, itemBounds) = sample
                    val background = image.colorAt(itemBounds.left + itemBounds.width * 0.04f, textBounds.center.y)
                    val bg = background.luminance()
                    // The menu's Text can supply its color at draw time, outside the text layout result.
                    val contrast = (textBounds.top.toInt() until textBounds.bottom.toInt()).maxOf { y ->
                        (textBounds.left.toInt() until textBounds.right.toInt()).maxOf { x ->
                            contrastRatio(Color(image.getPixel(x, y)).luminance(), bg)
                        }
                    }
                    assertTrue("$appearance $label contrast was $contrast, expected at least 4.5", contrast >= 4.5)
                }
            }
            assertTrue(host.instrumentation.uiAutomation.performGlobalAction(
                AccessibilityService.GLOBAL_ACTION_BACK
            ))
            withTimeout(5000) {
                while (nodes(host, scenario).any { node -> menuLabels.any { host.text(node, it) } }) delay(30)
            }
        }
    }

    private suspend fun awaitNode(
        host: HeaderTestHost,
        scenario: ActivityScenario<MainActivity>,
        match: (SemanticsNode) -> Boolean
    ): SemanticsNode = withTimeout(8000) {
        var found = nodes(host, scenario).firstOrNull(match)
        while (found == null) {
            delay(30)
            found = nodes(host, scenario).firstOrNull(match)
        }
        found
    }

    private fun nodes(host: HeaderTestHost, scenario: ActivityScenario<MainActivity>): List<SemanticsNode> {
        var result: Result<List<SemanticsNode>>? = null
        scenario.onActivity { result = runCatching { host.nodes(it) } }
        return checkNotNull(result).getOrThrow()
    }

    private fun <T> onMain(host: HeaderTestHost, block: () -> T): T {
        var result: Result<T>? = null
        // Rethrow on the test thread so an assertion failure still runs fixture cleanup.
        host.main { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }

    private fun SemanticsNode.tag() = config.getOrNull(SemanticsProperties.TestTag)

    private fun SemanticsNode.clickOwner() = generateSequence(this) { it.parent }
        .first { it.config.getOrNull(SemanticsActions.OnClick) != null }

    private fun click(host: HeaderTestHost, node: SemanticsNode) {
        onMain(host) { assertTrue(node.clickOwner().config[SemanticsActions.OnClick].action?.invoke() == true) }
    }

    private fun SemanticsNode.textColor(): Color {
        val layouts = mutableListOf<TextLayoutResult>()
        assertTrue(config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts) == true)
        return layouts.single().layoutInput.style.color
    }

    private fun SemanticsNode.screenBounds() = Rect(layoutInfo.coordinates.localToScreen(Offset.Zero), size.toSize())

    private fun Bitmap.colorAt(x: Float, y: Float) = Color(getPixel(x.toInt(), y.toInt()))

    private fun screenshot(host: HeaderTestHost, inspect: (Bitmap) -> Unit) {
        val image = requireNotNull(host.instrumentation.uiAutomation.takeScreenshot())
        try {
            inspect(image)
        } finally {
            image.recycle()
        }
    }

    private fun assertContrast(label: String, foreground: Color, background: Color, minimum: Double) {
        val fg = foreground.compositeOver(background).luminance()
        val bg = background.luminance()
        val ratio = contrastRatio(fg, bg)
        assertTrue("$label contrast was $ratio, expected at least $minimum", ratio >= minimum)
    }

    private fun contrastRatio(foreground: Float, background: Float) =
        (maxOf(foreground, background) + 0.05) / (minOf(foreground, background) + 0.05)

    private fun nightMode(host: HeaderTestHost, mode: String = ""): String =
        ParcelFileDescriptor.AutoCloseInputStream(
            host.instrumentation.uiAutomation.executeShellCommand("cmd uimode night $mode".trimEnd())
        ).bufferedReader().use { it.readText().trim() }
}
