package me.bubblespark.tidilo.data

import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import me.bubblespark.tidilo.R

internal class AddEntityDialogTestControls(private val host: HeaderTestHost) {
    private val title = host.context.getString(R.string.detail_add_entity_title)

    suspend fun clickLabel(label: String) {
        await("click \"$label\"") { nodes ->
            val labelNode = nodes.firstOrNull { host.text(it, label) || host.description(it, label) }
            val button = generateSequence(labelNode) { it.parent }
                .firstOrNull { SemanticsActions.OnClick in it.config }
            button != null && SemanticsProperties.Disabled !in button.config &&
                button.config[SemanticsActions.OnClick].action?.invoke() == true
        }
    }

    suspend fun confirm() = clickLabel(host.context.getString(R.string.common_ok))

    suspend fun enter(value: String) {
        await("set input to \"$value\"") { nodes ->
            input(nodes)?.config?.getOrNull(SemanticsActions.SetText)
                ?.action?.invoke(AnnotatedString(value)) == true
        }
        awaitInput(value)
    }

    suspend fun awaitInput(expected: String) {
        await("display input \"$expected\"") { nodes ->
            input(nodes)?.config?.getOrNull(SemanticsProperties.EditableText)?.text == expected
        }
    }

    private fun input(nodes: List<SemanticsNode>): SemanticsNode? = nodes.firstOrNull {
        SemanticsActions.SetText in it.config && SemanticsProperties.EditableText in it.config
    }

    private suspend fun await(step: String, condition: (List<SemanticsNode>) -> Boolean) {
        val completed = withTimeoutOrNull(5000) {
            var matched = false
            while (!matched) {
                host.main { matched = condition(dialogNodes()) }
                if (!matched) delay(30)
            }
            true
        }
        check(completed == true) { "Could not $step in the $title dialog" }
    }

    private fun dialogNodes(): List<SemanticsNode> {
        fun roots(view: View): Sequence<ViewRootForTest> = when (view) {
            is ViewRootForTest -> sequenceOf(view)
            is ViewGroup -> (0 until view.childCount).asSequence().flatMap { roots(view.getChildAt(it)) }
            else -> emptySequence()
        }
        fun descendants(node: SemanticsNode): Sequence<SemanticsNode> =
            sequenceOf(node) + node.children.asSequence().flatMap(::descendants)

        return WindowInspector.getGlobalWindowViews().asSequence()
            .flatMap(::roots)
            .map { root ->
                descendants(root.semanticsOwner.unmergedRootSemanticsNode)
                    .filter { it.layoutInfo.isPlaced }
                    .toList()
            }
            .firstOrNull { nodes -> nodes.any { host.text(it, title) } }
            .orEmpty()
    }
}
