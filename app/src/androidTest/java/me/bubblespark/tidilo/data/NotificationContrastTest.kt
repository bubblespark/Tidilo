package me.bubblespark.tidilo.data

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.view.View
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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
import kotlin.math.ceil

class NotificationContrastTest {
    /** Uses a dedicated emulator because opening the production route marks unread history as read. */
    @Test
    fun productionNotificationRouteHasReadableTitlesAndActionsAcrossThemeChanges(): Unit = runBlocking {
        val host = HeaderTestHost()
        val db = AppDatabase.getInstance(host.context)
        val notifications = db.importNotificationDao()
        val originalTheme = AppConfig.currentTheme.value
        val originalAiMode = AppConfig.aiAnalysisMode.value
        val notificationPrefs = host.context.getSharedPreferences("tidilo_notifications", Context.MODE_PRIVATE)
        val originalPromptRequested = notificationPrefs.all["permission_requested"] as? Boolean
        val originalPermission = notificationPermission(host)
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
        var testFailure: Throwable? = null

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
            launchNotifications(host, notificationPrefs).use { scenario ->
                verifyAppearance(host, scenario, titles, actions, AppTheme.DEFAULT, dark = true)
                withTimeout(STATE_CHANGE_TIMEOUT_MS) { notifications.observeUnreadCount().first { it == 0 } }

                nightMode(host, "no")
                verifyAppearance(host, scenario, titles, actions, AppTheme.DEFAULT, dark = false)
                AppConfig.setTheme(AppTheme.MODERN)
                verifyAppearance(host, scenario, titles, actions, AppTheme.MODERN, dark = false)
                nightMode(host, "yes")
                verifyAppearance(host, scenario, titles, actions, AppTheme.MODERN, dark = true)
            }
            assertEquals(0, notifications.observeUnreadCount().first())
            launchNotifications(host, notificationPrefs).use { scenario ->
                verifyAppearance(host, scenario, titles, actions, AppTheme.MODERN, dark = true)
                click(host, awaitNode(host, scenario) { it.tag() == importAction })
                val delete = host.context.getString(R.string.common_delete)
                click(host, awaitNode(host, scenario) { host.text(it, delete) })
                withTimeout(STATE_CHANGE_TIMEOUT_MS) {
                    while (db.importTaskDao().getTaskById(key) != null) delay(POLL_INTERVAL_MS)
                }
            }
        } catch (failure: Throwable) {
            testFailure = failure
            throw failure
        } finally {
            withContext(NonCancellable) {
                val cleanup = listOf<suspend () -> Unit>(
                    { AppConfig.setTheme(originalTheme) },
                    { AppConfig.setAiAnalysisMode(originalAiMode) },
                    {
                        nightMode(host, originalNightMode)
                        assertEquals("Night mode: $originalNightMode", nightMode(host))
                    },
                    {
                        val editor = notificationPrefs.edit()
                        if (originalPromptRequested == null) editor.remove("permission_requested")
                        else editor.putBoolean("permission_requested", originalPromptRequested)
                        assertTrue("Could not restore notification prompt preference", editor.commit())
                        assertEquals("Test changed notification permission", originalPermission, notificationPermission(host))
                    },
                    {
                        if (historyId != 0L) db.withTransaction {
                            notifications.deleteById(historyId)
                            db.importTaskDao().deleteById(key)
                            db.openHelper.writableDatabase.execSQL("DELETE FROM rss_feeds WHERE id = ?", arrayOf(key))
                        }
                    }
                )
                val failures = cleanup.mapNotNull { runCatching { it() }.exceptionOrNull() }
                if (failures.isNotEmpty()) {
                    val failure = testFailure ?: AssertionError("Notification contrast test cleanup failed")
                    failures.forEach(failure::addSuppressed)
                    if (testFailure == null) throw failure
                }
            }
        }
    }

    private suspend fun launchNotifications(
        host: HeaderTestHost,
        notificationPrefs: SharedPreferences
    ): ActivityScenario<MainActivity> {
        val expectsPrompt = notificationPermission(host) != PackageManager.PERMISSION_GRANTED &&
            !notificationPrefs.getBoolean("permission_requested", false)
        val scenario = ActivityScenario.launch<MainActivity>(Intent(host.context, MainActivity::class.java)
            .putExtra(NotificationCoordinator.EXTRA_DESTINATION, NotificationCategory.IMPORT_ERROR.destination))
        try {
            if (expectsPrompt) dismissNotificationPrompt(host)
            return scenario
        } catch (failure: Throwable) {
            runCatching { scenario.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    private suspend fun dismissNotificationPrompt(host: HeaderTestHost) {
        val automation = host.instrumentation.uiAutomation
        withTimeout(UI_READY_TIMEOUT_MS) {
            while (automation.rootInActiveWindow?.packageName?.toString()?.endsWith(".permissioncontroller") != true) {
                delay(POLL_INTERVAL_MS)
            }
            assertTrue("Could not dismiss the notification permission prompt",
                automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            while (automation.rootInActiveWindow?.packageName != host.context.packageName) delay(POLL_INTERVAL_MS)
        }
    }

    private fun notificationPermission(host: HeaderTestHost): Int =
        host.context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)

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
        val iconDescription = host.context.getString(R.string.notification_more_actions)
        val retryLabel = host.context.getString(R.string.common_retry)
        val rows = awaitStableFrame(host, scenario, "$appearance notification rows") { activity, nodes ->
            val rtl = activity.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
            val titleSamples = titles.map { title ->
                val node = nodes.firstOrNull { host.text(it, title) } ?: return@awaitStableFrame null
                val color = node.textColorOrNull() ?: return@awaitStableFrame null
                val bounds = node.screenBounds()
                val margin = if (rtl) bounds.right + 8f else bounds.left - 8f
                Sample(bounds, Offset(margin, bounds.center.y), color)
            }
            val iconSamples = actions.map { tag ->
                val button = nodes.firstOrNull { it.tag() == tag } ?: return@awaitStableFrame null
                val icon = button.children.firstOrNull { host.description(it, iconDescription) }
                    ?: return@awaitStableFrame null
                assertNull(button.config.getOrNull(SemanticsProperties.Disabled))
                val bounds = button.screenBounds()
                Sample(icon.screenBounds(), Offset(bounds.left + bounds.width * 0.1f, bounds.center.y))
            }
            // Each unresolved row exposes Retry as a visible tonal button beside its overflow menu.
            val retryLabels = nodes.filter { host.text(it, retryLabel) }
            if (retryLabels.size != actions.size) return@awaitStableFrame null
            val retrySamples = retryLabels.map { label ->
                val button = label.clickOwner()
                assertNull(button.config.getOrNull(SemanticsProperties.Disabled))
                val bounds = button.screenBounds()
                Sample(label.screenBounds(), Offset(bounds.left + bounds.width * 0.06f, bounds.center.y))
            }
            FrameSamples(titleSamples + iconSamples + retrySamples, expectedCanvas)
        }
        titles.zip(rows.take(titles.size)).forEach { (title, ratio) ->
            assertContrast("$appearance $title", ratio, NORMAL_TEXT_CONTRAST)
        }
        actions.zip(rows.drop(titles.size)).forEach { (tag, ratio) ->
            assertContrast("$appearance $tag", ratio, ICON_CONTRAST)
        }
        rows.drop(titles.size + actions.size).forEachIndexed { index, ratio ->
            assertContrast("$appearance Retry $index", ratio, NORMAL_TEXT_CONTRAST)
        }

        val menuLabels = listOf(R.string.common_delete).map(host.context::getString)
        actions.forEach { tag ->
            click(host, awaitNode(host, scenario) { it.tag() == tag })
            val menu = awaitStableFrame(host, scenario, "$appearance $tag menu") { _, nodes ->
                FrameSamples(menuLabels.map { label ->
                    val node = nodes.firstOrNull { host.text(it, label) } ?: return@awaitStableFrame null
                    val item = node.clickOwner()
                    assertNull(item.config.getOrNull(SemanticsProperties.Disabled))
                    val textBounds = node.screenBounds()
                    val itemBounds = item.screenBounds()
                    Sample(textBounds, Offset(itemBounds.left + itemBounds.width * 0.04f, textBounds.center.y))
                })
            }
            menuLabels.zip(menu).forEach { (label, ratio) ->
                assertContrast("$appearance $label", ratio, NORMAL_TEXT_CONTRAST)
            }
            assertTrue(host.instrumentation.uiAutomation.performGlobalAction(
                AccessibilityService.GLOBAL_ACTION_BACK
            ))
            withTimeout(STATE_CHANGE_TIMEOUT_MS) {
                while (nodes(host, scenario).any { node -> menuLabels.any { host.text(node, it) } }) delay(POLL_INTERVAL_MS)
            }
        }
    }

    private data class Sample(val bounds: Rect, val backgroundPoint: Offset, val textColor: Color? = null)

    private data class FrameSamples(val samples: List<Sample>, val canvas: Color? = null)

    private data class Measurement(val bounds: Rect, val background: Color, val ratio: Double)

    private suspend fun awaitStableFrame(
        host: HeaderTestHost,
        scenario: ActivityScenario<MainActivity>,
        label: String,
        locate: (MainActivity, List<SemanticsNode>) -> FrameSamples?
    ): List<Double> {
        var previous: List<Measurement>? = null
        var stableFrames = 0
        val measurements = withTimeoutOrNull(UI_READY_TIMEOUT_MS) {
            while (stableFrames < REQUIRED_STABLE_FRAMES) {
                val frame = onActivity(scenario) { locate(it, host.nodes(it)) }
                val current = frame?.let { screenshot(host) { image -> measureFrame(image, frame) } }
                // Geometry and pixels must settle. Popup opacity can change without moving its layout bounds.
                stableFrames = when {
                    current == null -> 0
                    current == previous -> stableFrames + 1
                    else -> 1
                }
                previous = current
                if (stableFrames < REQUIRED_STABLE_FRAMES) delay(POLL_INTERVAL_MS)
            }
            previous
        }
        return checkNotNull(measurements) { "$label did not settle inside the screenshot viewport" }.map { it.ratio }
    }

    private fun measureFrame(image: Bitmap, frame: FrameSamples): List<Measurement>? = frame.samples.map { sample ->
        val bounds = sample.bounds
        val point = sample.backgroundPoint
        if (bounds.isEmpty || bounds.left < 0 || bounds.top < 0 ||
            bounds.right > image.width || bounds.bottom > image.height ||
            point.x !in 0f..<image.width.toFloat() || point.y !in 0f..<image.height.toFloat()) return null
        val background = image.colorAt(point.x, point.y)
        val textColor = sample.textColor
        if (textColor != null && frame.canvas != null && background != frame.canvas) return null
        val bg = background.luminance()
        val ratio = if (textColor != null) {
            contrastRatio(textColor.compositeOver(background).luminance(), bg)
        } else {
            (ceil(bounds.top).toInt() until bounds.bottom.toInt()).maxOfOrNull { y ->
                (ceil(bounds.left).toInt() until bounds.right.toInt()).maxOfOrNull { x ->
                    contrastRatio(Color(image.getPixel(x, y)).luminance(), bg)
                } ?: 1.0
            } ?: 1.0
        }
        Measurement(bounds, background, ratio)
    }

    private suspend fun awaitNode(
        host: HeaderTestHost,
        scenario: ActivityScenario<MainActivity>,
        match: (SemanticsNode) -> Boolean
    ): SemanticsNode = withTimeout(UI_READY_TIMEOUT_MS) {
        var found = nodes(host, scenario).firstOrNull(match)
        while (found == null) {
            delay(POLL_INTERVAL_MS)
            found = nodes(host, scenario).firstOrNull(match)
        }
        found
    }

    private fun nodes(host: HeaderTestHost, scenario: ActivityScenario<MainActivity>): List<SemanticsNode> =
        onActivity(scenario) { host.nodes(it) }

    private fun <T> onActivity(scenario: ActivityScenario<MainActivity>, block: (MainActivity) -> T): T {
        var result: Result<T>? = null
        scenario.onActivity { result = runCatching { block(it) } }
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

    private fun SemanticsNode.textColorOrNull(): Color? {
        val layouts = mutableListOf<TextLayoutResult>()
        if (config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts) != true) return null
        return layouts.singleOrNull()?.layoutInput?.style?.color
    }

    private fun SemanticsNode.screenBounds() = Rect(layoutInfo.coordinates.localToScreen(Offset.Zero), size.toSize())

    private fun Bitmap.colorAt(x: Float, y: Float) = Color(getPixel(x.toInt(), y.toInt()))

    private fun <T> screenshot(host: HeaderTestHost, inspect: (Bitmap) -> T): T {
        val image = requireNotNull(host.instrumentation.uiAutomation.takeScreenshot())
        return try {
            inspect(image)
        } finally {
            image.recycle()
        }
    }

    private fun assertContrast(label: String, ratio: Double, minimum: Double) {
        assertTrue("$label contrast was $ratio, expected at least $minimum", ratio >= minimum)
    }

    private fun contrastRatio(foreground: Float, background: Float) =
        (maxOf(foreground, background) + 0.05) / (minOf(foreground, background) + 0.05)

    private fun nightMode(host: HeaderTestHost, mode: String = ""): String =
        shell(host, "cmd uimode night $mode".trimEnd())

    private fun shell(host: HeaderTestHost, command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(
            host.instrumentation.uiAutomation.executeShellCommand(command)
        ).bufferedReader().use { it.readText().trim() }

    private companion object {
        const val NORMAL_TEXT_CONTRAST = 4.5
        const val ICON_CONTRAST = 3.0
        const val STATE_CHANGE_TIMEOUT_MS = 5000L
        const val UI_READY_TIMEOUT_MS = 8000L
        const val POLL_INTERVAL_MS = 30L
        const val REQUIRED_STABLE_FRAMES = 4
    }
}
