package me.bubblespark.tidilo.data

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.MainActivity
import me.bubblespark.tidilo.ui.screens.library.AI_REVIEW_STATUS_ERROR_TAG
import me.bubblespark.tidilo.ui.screens.library.AI_REVIEW_STATUS_INFO_TAG
import org.junit.Assert.assertTrue

internal class HeaderTestHost {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    fun launch(destination: String? = null) = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply {
            destination?.let { putExtra(me.bubblespark.tidilo.data.notifications.NotificationCoordinator.EXTRA_DESTINATION, it) }
        }) as ComponentActivity
    fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun roots(view: View, out: MutableList<ViewRootForTest>) {
        when (view) {
            is ViewRootForTest -> out.add(view)
            is ViewGroup -> for (i in 0 until view.childCount) {
                roots(view.getChildAt(i), out)
            }
        }
    }
    fun nodes(
        activity: ComponentActivity,
        useUnmergedTree: Boolean = true,
        includeOtherWindows: Boolean = true
    ): List<SemanticsNode> {
        val collectedRoots = mutableListOf<ViewRootForTest>()
        roots(activity.window.decorView, collectedRoots)
        if (includeOtherWindows) {
            runCatching {
                val wmg = Class.forName("android.view.WindowManagerGlobal")
                val instance = wmg.getMethod("getInstance").invoke(null)
                val mViews = wmg.getDeclaredField("mViews").apply { isAccessible = true }
                @Suppress("UNCHECKED_CAST")
                val views = (mViews.get(instance) as? List<View>)?.let { ArrayList(it) } ?: emptyList()
                views.forEach { roots(it, collectedRoots) }
            }.onFailure { android.util.Log.w("HeaderTestHost", "WindowManagerGlobal root lookup failed", it) }
        }
        val result = mutableListOf<SemanticsNode>()
        fun visit(node: SemanticsNode) {
            result.add(node)
            node.children.forEach(::visit)
        }
        collectedRoots.distinct().forEach { root ->
            runCatching {
                val owner = root.semanticsOwner
                visit(if (useUnmergedTree) owner.unmergedRootSemanticsNode else owner.rootSemanticsNode)
            }.onFailure { android.util.Log.w("HeaderTestHost", "Semantics traversal failed", it) }
        }
        return result
    }
    suspend fun await(activity: ComponentActivity, predicate: (SemanticsNode) -> Boolean): SemanticsNode =
        await(activity, includeOtherWindows = true, predicate)

    suspend fun await(
        activity: ComponentActivity,
        includeOtherWindows: Boolean,
        predicate: (SemanticsNode) -> Boolean
    ): SemanticsNode = withTimeout(8000) {
        var found: SemanticsNode? = null
        while (found == null) {
            main { found = nodes(activity, includeOtherWindows = includeOtherWindows).firstOrNull(predicate) }
            if (found == null) delay(100)
        }
        requireNotNull(found)
    }
    suspend fun awaitAbsent(activity: ComponentActivity, predicate: (SemanticsNode) -> Boolean) = withTimeout(8000) {
        while (true) {
            var present = true
            main { present = nodes(activity).any(predicate) }
            if (!present) break
            delay(100)
        }
    }
    fun description(node: SemanticsNode, value: String) = node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(value) == true
    fun text(node: SemanticsNode, value: String) = node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == value } == true
    fun scroll(activity: ComponentActivity, amount: Float) {
        main {
            val scroll = nodes(activity).first { it.config.getOrNull(SemanticsActions.ScrollBy) != null }
            check(scroll.config[SemanticsActions.ScrollBy].action?.invoke(0f, amount) == true)
        }
    }
    fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("tidiloDebugCapture") != "true") return
        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(150)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        try {
            java.io.File(context.cacheDir, "issue137-$name.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally { bitmap.recycle() }
    }
}

internal fun HeaderTestHost.assertNoAiReviewStatusIcon(activity: ComponentActivity, expectedTitle: String) {
    main {
        val tags = setOf(AI_REVIEW_STATUS_INFO_TAG, AI_REVIEW_STATUS_ERROR_TAG)
        val reviewNodes = nodes(activity, includeOtherWindows = false)
        assertTrue("Review window must show $expectedTitle", reviewNodes.any { text(it, expectedTitle) })
        assertTrue("Review status icon should be absent", reviewNodes.none {
            it.config.getOrNull(SemanticsProperties.TestTag) in tags
        })
    }
}
