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

internal class HeaderTestHost {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    fun launch() = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
    fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun roots(view: View): List<ViewRootForTest> = when (view) {
        is ViewRootForTest -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { roots(view.getChildAt(it)) }
        else -> emptyList()
    }
    fun nodes(activity: ComponentActivity): List<SemanticsNode> {
        fun visit(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::visit)
        return roots(activity.window.decorView).flatMap { visit(it.semanticsOwner.unmergedRootSemanticsNode) }
    }
    suspend fun await(activity: ComponentActivity, predicate: (SemanticsNode) -> Boolean): SemanticsNode = withTimeout(8000) {
        var found: SemanticsNode? = null
        while (found == null) {
            main { found = nodes(activity).firstOrNull(predicate) }
            if (found == null) delay(30)
        }
        requireNotNull(found)
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
