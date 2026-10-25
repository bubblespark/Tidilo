package me.bubblespark.tidilo.data

import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.MainActivity
import me.bubblespark.tidilo.data.db.AppDatabase
import me.bubblespark.tidilo.data.db.ImportNotificationEntity
import me.bubblespark.tidilo.data.db.NotificationType
import me.bubblespark.tidilo.ui.screens.importscreen.ImportScreen
import me.bubblespark.tidilo.ui.screens.importscreen.ImportViewModel
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression test for GitHub Issue #114:
 * Verifies that opening the notification dropdown menu on ImportScreen when notifications
 * are present does not throw IllegalStateException (intrinsic measurement of SubcomposeLayout)
 * and correctly displays the notification item.
 */
class ImportNotificationMenuTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val database = AppDatabase.getInstance(context)
    private val notificationDao = database.importNotificationDao()

    @Before
    fun setUp() = runBlocking {
        notificationDao.deleteAll()
    }

    @After
    fun tearDown() = runBlocking {
        notificationDao.deleteAll()
    }

    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (predicate(node)) return node
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { visit(it)?.let { found -> return found } }
            }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
    }

    private suspend fun awaitNode(label: String, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo = try {
        withTimeout(8000) {
            var node = find(predicate)
            while (node == null) {
                delay(50)
                node = find(predicate)
            }
            node
        }
    } catch (error: TimeoutCancellationException) {
        val texts = mutableListOf<String>()
        val descs = mutableListOf<String>()
        find { node ->
            node.text?.let { texts.add(it.toString()) }
            node.contentDescription?.let { descs.add(it.toString()) }
            false
        }
        throw AssertionError("Missing accessible node: $label; visible texts: $texts; descriptions: $descs", error)
    }

    private fun click(node: AccessibilityNodeInfo) {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) {
            target = target.parent
        }
        assertTrue("Expected a clickable ancestor or node for notification icon", target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    }

    @Test
    fun testPopulatedNotificationMenuOpensWithoutCrashAndDisplaysItem(): Unit = runBlocking {
        // 1. Seed a duplicate import notification
        val notification = ImportNotificationEntity(
            url = "https://regression-test.example/article-1",
            domain = "regression-test.example",
            type = NotificationType.WARNING.name,
            message = "Already imported bookmark",
            existingBookmarkId = "test-bookmark-id-1",
            isRead = false,
            createdAt = System.currentTimeMillis()
        )
        notificationDao.insert(notification)

        // 2. Launch activity and display ImportScreen
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as ComponentActivity

        try {
            instrumentation.runOnMainSync {
                val viewModel = ViewModelProvider(activity)[ImportViewModel::class.java]
                activity.setContent {
                    MaterialTheme {
                        ImportScreen(viewModel = viewModel)
                    }
                }
            }

            // 3. Find and click notification bell icon
            val bellNode = awaitNode("Notification Bell Icon") {
                it.contentDescription?.toString()?.startsWith("Notifications") == true
            }
            click(bellNode)

            // 4. Assert that popup menu opened and displays notification details without crashing
            awaitNode("Notification message row") {
                it.text?.toString() == "Already imported bookmark"
            }
            awaitNode("Notification domain row") {
                it.text?.toString() == "regression-test.example"
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
