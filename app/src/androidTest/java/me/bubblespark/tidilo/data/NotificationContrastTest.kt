package me.bubblespark.tidilo.data

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.toSize
import androidx.room.withTransaction
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
import kotlin.math.ceil

/**
 * Launches the real notification route. A `ComposeTestRule` is not used: its effect dispatcher runs the
 * launch-destination navigation before `NavHost` sets its graph, so nodes are read from all windows here.
 */
class NotificationContrastTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    /** Uses a dedicated emulator because opening the production route marks unread history as read. */
    @Test
    fun productionNotificationRouteHasReadableTitlesAndActionsAcrossThemeChanges(): Unit = runBlocking {
        val db = AppDatabase.getInstance(context)
        val notifications = db.importNotificationDao()
        val originalTheme = AppConfig.currentTheme.value
        val originalAiMode = AppConfig.aiAnalysisMode.value
        val originalBlur = AppConfig.overlayBlurEnabled.value
        val originalPermission = notificationPermission()
        val originalNightMode = nightMode().substringAfter("Night mode: ")
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
            AppConfig.setOverlayBlurEnabled(false)
            AppConfig.setAiAnalysisMode(AiAnalysisMode.DISABLE)
            AppConfig.setTheme(AppTheme.DEFAULT)
            setNightMode(dark = true)
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
            launchNotifications().use {
                verifyAppearance(titles, actions, AppTheme.DEFAULT, dark = true)
                withTimeout(STATE_CHANGE_TIMEOUT_MS) { notifications.observeUnreadCount().first { it == 0 } }

                setNightMode(dark = false)
                verifyAppearance(titles, actions, AppTheme.DEFAULT, dark = false)
                AppConfig.setTheme(AppTheme.MODERN)
                verifyAppearance(titles, actions, AppTheme.MODERN, dark = false)
                setNightMode(dark = true)
                verifyAppearance(titles, actions, AppTheme.MODERN, dark = true)
            }
            assertEquals(0, notifications.observeUnreadCount().first())
            launchNotifications().use {
                verifyAppearance(titles, actions, AppTheme.MODERN, dark = true)
                click(awaitNode { it.tag() == importAction })
                val delete = context.getString(R.string.common_delete)
                click(awaitNode { it.hasText(delete) })
                withTimeout(STATE_CHANGE_TIMEOUT_MS) {
                    while (withContext(Dispatchers.IO) { db.importTaskDao().getTaskById(key) } != null) {
                        delay(POLL_INTERVAL_MS)
                    }
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
                    { AppConfig.setOverlayBlurEnabled(originalBlur) },
                    {
                        nightMode(originalNightMode)
                        assertEquals("Night mode: $originalNightMode", nightMode())
                    },
                    { assertEquals("Test changed notification permission", originalPermission, notificationPermission()) },
                    {
                        if (historyId != 0L) withContext(Dispatchers.IO) {
                            db.withTransaction {
                                notifications.deleteById(historyId)
                                db.importTaskDao().deleteById(key)
                                db.openHelper.writableDatabase.execSQL("DELETE FROM rss_feeds WHERE id = ?", arrayOf(key))
                            }
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

    /** `MainActivity` does not ask for notification permission on launch, so there is no prompt to dismiss. */
    private fun launchNotifications(): ActivityScenario<MainActivity> =
        ActivityScenario.launch(Intent(context, MainActivity::class.java)
            .putExtra(NotificationCoordinator.EXTRA_DESTINATION, NotificationCategory.IMPORT_ERROR.destination))

    private fun notificationPermission(): Int = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)

    /** Switches night mode and waits until the app configuration reflects it. */
    private suspend fun setNightMode(dark: Boolean) {
        nightMode(if (dark) "yes" else "no")
        val expected = if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        withTimeout(STATE_CHANGE_TIMEOUT_MS) {
            while (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK != expected) {
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun verifyAppearance(titles: List<String>, actions: List<String>, theme: AppTheme, dark: Boolean) {
        val expectedCanvas = when (theme) {
            AppTheme.DEFAULT -> if (dark) dynamicDarkColorScheme(context).background
                else dynamicLightColorScheme(context).background
            AppTheme.MODERN -> if (dark) ModernDarkColorScheme.background else ModernLightColorScheme.background
        }
        val appearance = "$theme ${if (dark) "dark" else "light"}"
        // `titles` is history, import, RSS; `actions` belong to the import and RSS rows in that order.
        val iconDescriptions = titles.takeLast(actions.size)
            .map { context.getString(R.string.notification_more_actions_item, it) }
        val retryLabel = context.getString(R.string.common_retry)
        val rtl = context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val rows = awaitStableFrame("$appearance notification rows") { nodes ->
            val titleSamples = titles.map { title ->
                val node = nodes.firstOrNull { it.hasText(title) } ?: return@awaitStableFrame null
                val color = node.textColorOrNull() ?: return@awaitStableFrame null
                val bounds = node.screenBounds()
                val margin = if (rtl) bounds.right + 8f else bounds.left - 8f
                Sample(bounds, Offset(margin, bounds.center.y), color)
            }
            val iconSamples = actions.zip(iconDescriptions) { tag, iconDescription ->
                val button = nodes.firstOrNull { it.tag() == tag } ?: return@awaitStableFrame null
                val icon = button.children.firstOrNull { it.hasDescription(iconDescription) }
                    ?: return@awaitStableFrame null
                assertNull(button.config.getOrNull(SemanticsProperties.Disabled))
                val bounds = button.screenBounds()
                Sample(icon.screenBounds(), Offset(bounds.left + bounds.width * 0.1f, bounds.center.y))
            }
            // Each unresolved row exposes Retry as a visible tonal button beside its overflow menu.
            val retryLabels = nodes.filter { it.hasText(retryLabel) }
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

        val menuLabels = listOf(R.string.common_delete).map(context::getString)
        actions.forEach { tag ->
            click(awaitNode { it.tag() == tag })
            val menu = awaitStableFrame("$appearance $tag menu") { nodes ->
                FrameSamples(menuLabels.map { label ->
                    val node = nodes.firstOrNull { it.hasText(label) } ?: return@awaitStableFrame null
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
            assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            withTimeout(STATE_CHANGE_TIMEOUT_MS) {
                while (onMain { nodes -> nodes.any { node -> menuLabels.any { node.hasText(it) } } }) delay(POLL_INTERVAL_MS)
            }
        }
    }

    private data class Sample(val bounds: Rect, val backgroundPoint: Offset, val textColor: Color? = null)

    private data class FrameSamples(val samples: List<Sample>, val canvas: Color? = null)

    private data class Measurement(val bounds: Rect, val background: Color, val ratio: Double)

    /**
     * Waits until the samples can be located, then compares at most [MAX_SAMPLES] screenshots taken
     * [POLL_INTERVAL_MS] apart until [REQUIRED_STABLE_FRAMES] consecutive frames measure the same.
     */
    private suspend fun awaitStableFrame(label: String, locate: (List<SemanticsNode>) -> FrameSamples?): List<Double> {
        withTimeout(UI_READY_TIMEOUT_MS) { while (onMain(locate) == null) delay(POLL_INTERVAL_MS) }
        instrumentation.waitForIdleSync()
        var previous: List<Measurement>? = null
        var stableFrames = 0
        repeat(MAX_SAMPLES) { sample ->
            if (sample > 0) delay(POLL_INTERVAL_MS)
            val frame = onMain(locate)
            val current = frame?.let { screenshot { image -> measureFrame(image, frame) } }
            // Geometry and pixels must settle. Popup opacity can change without moving its layout bounds.
            stableFrames = when {
                current == null -> 0
                current == previous -> stableFrames + 1
                else -> 1
            }
            previous = current
            if (stableFrames >= REQUIRED_STABLE_FRAMES) return checkNotNull(current).map { it.ratio }
        }
        throw AssertionError("$label did not settle inside the screenshot viewport after $MAX_SAMPLES samples")
    }

    /** Runs [block] on the main thread over every semantics node in every app window. */
    private fun <T> onMain(block: (List<SemanticsNode>) -> T): T {
        var result: Result<T>? = null
        // Rethrow on the test thread so an assertion failure still runs fixture cleanup.
        instrumentation.runOnMainSync { result = runCatching { block(allNodes()) } }
        return checkNotNull(result).getOrThrow()
    }

    private fun allNodes(): List<SemanticsNode> {
        val roots = mutableListOf<ViewRootForTest>()
        fun collect(view: View) {
            when (view) {
                is ViewRootForTest -> roots += view
                is ViewGroup -> for (index in 0 until view.childCount) collect(view.getChildAt(index))
            }
        }
        WindowInspector.getGlobalWindowViews().forEach(::collect)
        val nodes = mutableListOf<SemanticsNode>()
        fun visit(node: SemanticsNode) {
            nodes += node
            node.children.forEach(::visit)
        }
        roots.distinct().forEach { visit(it.semanticsOwner.unmergedRootSemanticsNode) }
        return nodes
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

    private suspend fun awaitNode(match: (SemanticsNode) -> Boolean): SemanticsNode = withTimeout(UI_READY_TIMEOUT_MS) {
        var found = onMain { nodes -> nodes.firstOrNull(match) }
        while (found == null) {
            delay(POLL_INTERVAL_MS)
            found = onMain { nodes -> nodes.firstOrNull(match) }
        }
        found
    }

    private fun SemanticsNode.hasText(value: String) =
        config.getOrNull(SemanticsProperties.Text)?.any { it.text == value } == true

    private fun SemanticsNode.hasDescription(value: String) =
        config.getOrNull(SemanticsProperties.ContentDescription)?.contains(value) == true

    private fun SemanticsNode.tag() = config.getOrNull(SemanticsProperties.TestTag)

    private fun SemanticsNode.clickOwner() = generateSequence(this) { it.parent }
        .first { it.config.getOrNull(SemanticsActions.OnClick) != null }

    private fun click(node: SemanticsNode) {
        assertTrue(onMain { node.clickOwner().config[SemanticsActions.OnClick].action?.invoke() == true })
    }

    private fun SemanticsNode.textColorOrNull(): Color? {
        val layouts = mutableListOf<TextLayoutResult>()
        if (config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts) != true) return null
        return layouts.singleOrNull()?.layoutInput?.style?.color
    }

    private fun SemanticsNode.screenBounds() = Rect(layoutInfo.coordinates.localToScreen(Offset.Zero), size.toSize())

    private fun Bitmap.colorAt(x: Float, y: Float) = Color(getPixel(x.toInt(), y.toInt()))

    private fun <T> screenshot(inspect: (Bitmap) -> T): T {
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
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

    private fun nightMode(mode: String = ""): String = shell("cmd uimode night $mode".trimEnd())

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)
        ).bufferedReader().use { it.readText().trim() }

    private companion object {
        const val NORMAL_TEXT_CONTRAST = 4.5
        const val ICON_CONTRAST = 3.0
        const val STATE_CHANGE_TIMEOUT_MS = 5000L
        const val UI_READY_TIMEOUT_MS = 8000L
        const val POLL_INTERVAL_MS = 100L
        const val REQUIRED_STABLE_FRAMES = 4
        const val MAX_SAMPLES = 10
    }
}
