package me.bubblespark.tidilo.data

import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.MainActivity
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.TidiloApplication
import me.bubblespark.tidilo.config.AppConfig
import me.bubblespark.tidilo.ui.screens.settings.StorageLoad
import me.bubblespark.tidilo.ui.screens.settings.StorageScreen
import me.bubblespark.tidilo.ui.screens.settings.StorageViewModel
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.*
import org.junit.Test

class StorageScreenTest {
    // No ComposeTestRule: it would drive MainActivity's composition on a test clock that the
    // accessibility-based E2E test below never advances. The isolated test hosts a plain ComponentActivity.

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

    private suspend fun awaitNode(
        label: String,
        onMissing: () -> Unit = {},
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo = try {
        withTimeout(8000) {
            var node = find(predicate)
            while (node == null) {
                onMissing()
                delay(100)
                node = find(predicate)
            }
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
        val previousBlur = AppConfig.overlayBlurEnabled.value
        AppConfig.setOverlayBlurEnabled(false)
        var launched: ComponentActivity? = null
        try {
            val activity = (instrumentation.startActivitySync(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as ComponentActivity).also { launched = it }
            fun scrollForward() {
                find { it.isVisibleToUser && it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            }
            click(awaitNode("Library menu") {
                it.contentDescription?.toString() == activity.getString(R.string.navigation_open)
            })
            awaitNode("Library rail Trash entry") {
                it.isVisibleToUser && it.text?.toString() == activity.getString(R.string.trash_title)
            }
            click(awaitNode("Rail Settings entry", onMissing = ::scrollForward) {
                it.isVisibleToUser && it.text?.toString() == activity.getString(R.string.title_settings)
            })
            awaitNode("Settings language entry") {
                it.isVisibleToUser && it.text?.toString() == activity.getString(R.string.settings_language_title)
            }
            click(awaitNode("Storage entry", onMissing = ::scrollForward) {
                it.text?.toString()?.startsWith(activity.getString(R.string.summary_title)) == true
            })
            awaitNode("Total Bookmarks") { it.text?.toString() == activity.getString(R.string.summary_total_bookmarks) }
            val clearBtn = awaitNode("Clear cache button", onMissing = ::scrollForward) {
                it.text?.toString() == activity.getString(R.string.summary_clear_cache_button)
            }
            awaitNode("Total cache size") { it.text?.toString() == activity.getString(R.string.summary_total) }
            click(clearBtn)
            awaitNode("Clear cache confirmation") {
                it.text?.toString() == activity.getString(R.string.summary_clear_cache_dialog_message)
            }
            click(awaitNode("Cancel clear cache") {
                it.text?.toString() == activity.getString(R.string.common_cancel)
            })
            instrumentation.runOnMainSync { activity.onBackPressedDispatcher.onBackPressed() }
            awaitNode("Returned Storage entry subtitle") { it.text?.toString() == activity.getString(R.string.summary_subtitle) }
        } finally {
            launched?.let { host -> instrumentation.runOnMainSync { host.finish() } }
            AppConfig.setOverlayBlurEnabled(previousBlur)
        }
    }

    @Test fun resumeWithoutStopRefreshesCacheSnapshot(): Unit = runBlocking {
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        lateinit var registry: LifecycleRegistry
        lateinit var model: StorageViewModel
        var initialized = false
        try {
            scenario.onActivity { activity ->
                val owner = object : LifecycleOwner {
                    override val lifecycle: Lifecycle get() = registry
                }
                registry = LifecycleRegistry(owner)
                registry.currentState = Lifecycle.State.RESUMED
                model = StorageViewModel(context.applicationContext as TidiloApplication)
                initialized = true
                activity.setContent {
                    CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                        TidiloTheme(darkTheme = false, dynamicColor = false, blurEnabled = false) { StorageScreen({}, model) }
                    }
                }
            }
            val previous = withTimeout(5000) {
                (model.cache.first { it is StorageLoad.Ready } as StorageLoad.Ready).value.checkedAt
            }
            instrumentation.runOnMainSync { registry.currentState = Lifecycle.State.STARTED }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { registry.currentState = Lifecycle.State.RESUMED }
            withTimeout(5000) {
                model.cache.first { (it as? StorageLoad.Ready)?.value?.checkedAt?.let { checked -> checked > previous } == true }
            }
        } finally {
            scenario.onActivity {
                if (initialized) {
                    registry.currentState = Lifecycle.State.DESTROYED
                    model.onHidden(); model.viewModelScope.cancel()
                }
            }
            scenario.close()
        }
    }
}
