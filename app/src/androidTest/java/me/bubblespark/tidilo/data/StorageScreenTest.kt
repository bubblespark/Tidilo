package me.bubblespark.tidilo.data

import android.content.Intent
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.MainActivity
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.TidiloApplication
import me.bubblespark.tidilo.ui.screens.settings.StorageLoad
import me.bubblespark.tidilo.ui.screens.settings.StorageScreen
import me.bubblespark.tidilo.ui.screens.settings.StorageViewModel
import org.junit.Assert.*
import org.junit.Test

class StorageScreenTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

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
            while (node == null) { delay(50); node = find(predicate) }
            node
        }
    } catch (error: TimeoutCancellationException) {
        val texts = mutableListOf<String>()
        find { node -> node.text?.let { texts.add(it.toString()) }; false }
        throw AssertionError("Missing accessible node: $label; visible text: $texts", error)
    }

    private fun click(node: AccessibilityNodeInfo) {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        assertTrue(target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    }

    @Test fun settingsStorageNavigationRefreshAndBack(): Unit = runBlocking {
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as ComponentActivity
        try {
            val settings = activity.getString(R.string.title_settings)
            click(awaitNode("Settings button") { it.contentDescription?.toString() == settings })
            awaitNode("Settings heading: ${activity.getString(R.string.settings_title)}") { it.text?.toString() == activity.getString(R.string.settings_title) }
            repeat(10) {
                if (find { it.text?.toString()?.startsWith(activity.getString(R.string.summary_title)) == true } == null) {
                    find { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    delay(150)
                }
            }
            click(awaitNode("Storage entry") { it.text?.toString()?.startsWith(activity.getString(R.string.summary_title)) == true })
            awaitNode("Total Bookmarks") { it.text?.toString() == activity.getString(R.string.summary_total_bookmarks) }
            delay(1000)
            instrumentation.waitForIdleSync()
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            try {
                File(context.getExternalFilesDir(null), "issue161-storage.png").outputStream().use {
                    screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            } finally { screenshot.recycle() }
            repeat(12) {
                if (find { it.text?.toString() == activity.getString(R.string.summary_clear_cache_button) } == null) {
                    find { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    delay(100)
                }
            }
            awaitNode("Clear cache button") { it.text?.toString() == activity.getString(R.string.summary_clear_cache_button) }
            awaitNode("Total cache size") { it.text?.toString() == activity.getString(R.string.summary_total) }
            instrumentation.runOnMainSync { activity.onBackPressedDispatcher.onBackPressed() }
            awaitNode("Returned Storage entry subtitle") { it.text?.toString() == activity.getString(R.string.summary_subtitle) }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun resumeWithoutStopRefreshesCacheSnapshot() = runBlocking {
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as ComponentActivity
        lateinit var registry: LifecycleRegistry
        lateinit var model: StorageViewModel
        var initialized = false
        try {
            instrumentation.runOnMainSync {
                val owner = object : LifecycleOwner {
                    override val lifecycle: Lifecycle get() = registry
                }
                registry = LifecycleRegistry(owner)
                registry.currentState = Lifecycle.State.RESUMED
                model = StorageViewModel(context.applicationContext as TidiloApplication)
                initialized = true
                activity.setContent {
                    CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                        MaterialTheme { StorageScreen({}, model) }
                    }
                }
            }
            suspend fun ready() = withTimeout(5000) {
                while (model.cache.value !is StorageLoad.Ready) delay(20)
                (model.cache.value as StorageLoad.Ready).value.checkedAt
            }
            val previous = ready()
            instrumentation.runOnMainSync { registry.currentState = Lifecycle.State.STARTED }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { registry.currentState = Lifecycle.State.RESUMED }
            withTimeout(5000) {
                while ((model.cache.value as? StorageLoad.Ready)?.value?.checkedAt?.let { it > previous } != true) delay(20)
            }
        } finally {
            instrumentation.runOnMainSync {
                if (initialized) {
                    registry.currentState = Lifecycle.State.DESTROYED
                    model.onHidden(); model.viewModelScope.cancel()
                }
                activity.finish()
            }
        }
    }
}
