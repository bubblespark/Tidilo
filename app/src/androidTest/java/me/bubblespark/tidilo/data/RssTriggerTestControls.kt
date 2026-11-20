package me.bubblespark.tidilo.data

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.bubblespark.tidilo.R

internal class RssTriggerTestControls(
    private val host: HeaderTestHost,
    private val activity: ComponentActivity
) {
    fun text(value: String): (SemanticsNode) -> Boolean = { host.text(it, value) }

    fun description(resource: Int, vararg args: Any): (SemanticsNode) -> Boolean {
        val label = activity.getString(resource, *args)
        return { host.description(it, label) }
    }

    fun action(node: SemanticsNode): SemanticsNode =
        generateSequence(node) { it.parent }.first { SemanticsActions.OnClick in it.config }

    suspend fun reveal(label: String, predicate: (SemanticsNode) -> Boolean): SemanticsNode =
        withContext(AndroidUiDispatcher.Main) {
            var direction = 1
            var viewport = ""
            var targetPlacement = "not composed"
            val found = withTimeoutOrNull(8000) {
                while (true) {
                    val nodes = host.nodes(activity, includeOtherWindows = false)
                    val list = nodes.firstOrNull { SemanticsActions.ScrollByOffset in it.config }
                    val header = nodes.firstOrNull { host.text(it, activity.getString(R.string.rss_title)) }
                    if (list == null || header == null || list.boundsInRoot.height <= 0f) {
                        delay(30)
                        continue
                    }
                    val bounds = list.boundsInRoot
                    val top = maxOf(bounds.top, header.boundsInRoot.bottom + 16 * activity.resources.displayMetrics.density)
                    viewport = "$bounds, clear top=$top"
                    val target = nodes.firstOrNull { it.layoutInfo.isPlaced && predicate(it) }
                    if (target != null) {
                        val targetBounds = target.boundsInRoot
                        targetPlacement = "$targetBounds, size=${target.size}"
                        if (targetBounds.top >= top && targetBounds.bottom <= bounds.bottom &&
                            targetBounds.height > 0f && targetBounds.height >= target.size.height - 1
                        ) return@withTimeoutOrNull target
                        if (targetBounds.height > 0f && targetBounds.top < top) direction = -1
                    }
                    val consumed = list.config[SemanticsActions.ScrollByOffset](
                        Offset(0f, direction * (bounds.bottom - top) / 3)
                    )
                    if (consumed.y == 0f) direction = -direction
                    delay(30)
                }
                null
            }
            found ?: throw AssertionError("Cannot reveal $label ($targetPlacement) in RSS viewport $viewport")
        }

    suspend fun clickRow(label: String, predicate: (SemanticsNode) -> Boolean) {
        reveal(label, predicate)
        val clicked = withContext(AndroidUiDispatcher.Main) {
            val current = host.nodes(activity, includeOtherWindows = false)
                .firstOrNull { it.layoutInfo.isPlaced && predicate(it) }
            current?.let(::click) == true
        }
        check(clicked) { "Cannot click $label in RSS settings" }
    }

    private fun dialogNodes(): List<SemanticsNode> = host.nodes(activity).filter { node ->
        generateSequence(node) { it.parent }.any { SemanticsProperties.IsDialog in it.config }
    }

    suspend fun dialogHas(predicate: (SemanticsNode) -> Boolean): Boolean =
        withContext(AndroidUiDispatcher.Main) { dialogNodes().any(predicate) }

    suspend fun awaitDialog(label: String, predicate: (SemanticsNode) -> Boolean) {
        val found = withTimeoutOrNull(5000) {
            while (!dialogHas(predicate)) delay(30)
            true
        }
        if (found != true) {
            val semantics = withContext(AndroidUiDispatcher.Main) {
                dialogNodes().joinToString("\n") { it.config.toString() }
            }
            throw AssertionError("Missing $label in RSS dialog:\n$semantics")
        }
    }

    suspend fun clickDialog(label: String, predicate: (SemanticsNode) -> Boolean = text(label)) {
        val clicked = withTimeoutOrNull(5000) {
            while (!withContext(AndroidUiDispatcher.Main) {
                dialogNodes().firstOrNull(predicate)?.let(::click) == true
            }) delay(30)
            true
        }
        check(clicked == true) { "Cannot click $label in RSS dialog" }
    }

    private fun click(node: SemanticsNode): Boolean {
        val target = generateSequence(node) { it.parent }
            .firstOrNull { SemanticsActions.OnClick in it.config } ?: return false
        return SemanticsProperties.Disabled !in target.config &&
            target.config[SemanticsActions.OnClick].action?.invoke() == true
    }
}
