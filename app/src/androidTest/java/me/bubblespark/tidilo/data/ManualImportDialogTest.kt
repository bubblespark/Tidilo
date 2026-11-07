package me.bubblespark.tidilo.data

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.ui.screens.importscreen.ImportUrlDialog
import org.junit.Assert.*
import org.junit.Test

class ManualImportDialogTest {
    @Test fun draftSurvivesSavedStateRestoration(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        var showDialog by mutableStateOf(true)
        var renderedShow = true
        var registry = SaveableStateRegistry(null) { true }
        try {
            host.main { activity.setContent { MaterialTheme {
                if (showDialog) CompositionLocalProvider(LocalSaveableStateRegistry provides registry) { ImportUrlDialog({}, {}) }
                val shown = showDialog
                SideEffect { renderedShow = shown }
            } } }
            val input = host.await(activity) { it.config.getOrNull(SemanticsActions.SetText) != null }
            host.main {
                input.config[SemanticsActions.SetText].action?.invoke(AnnotatedString("https://draft.invalid/article"))
            }
            host.await(activity) { it.config.getOrNull(SemanticsProperties.EditableText)?.text == "https://draft.invalid/article" }
            var saved: Map<String, List<Any?>> = emptyMap()
            host.main { saved = registry.performSave(); showDialog = false }
            withTimeout(3000) { while (renderedShow) delay(20) }
            host.main { registry = SaveableStateRegistry(saved) { true }; showDialog = true }
            withTimeout(3000) { while (!renderedShow) delay(20) }
            host.await(activity) { it.config.getOrNull(SemanticsActions.SetText) != null }
            host.main {
                assertTrue("Recreated dialog must retain the URL draft",
                    host.nodes(activity).any { it.config.getOrNull(SemanticsProperties.EditableText)?.text == "https://draft.invalid/article" })
            }
        } finally { host.main { activity.finish() } }
    }

    @Test fun confirmationSubmitsUrlOnce(): Unit = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        val submissions = mutableListOf<String>()
        try {
            host.main { activity.setContent { MaterialTheme { ImportUrlDialog({}, { submissions += it }) } } }
            val input = host.await(activity) { it.config.getOrNull(SemanticsActions.SetText) != null }
            host.main { input.config[SemanticsActions.SetText].action?.invoke(AnnotatedString("https://submit.invalid/article")) }
            val confirm = host.await(activity) { node ->
                host.text(node, host.context.getString(R.string.import_dialog_confirm)) &&
                    generateSequence(node) { it.parent }.firstOrNull { it.config.getOrNull(SemanticsActions.OnClick) != null }
                        ?.config?.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) == false
            }
            host.main {
                var target = confirm
                while (target.config.getOrNull(SemanticsActions.OnClick) == null) target = requireNotNull(target.parent)
                target.config[SemanticsActions.OnClick].action?.invoke()
                target.config[SemanticsActions.OnClick].action?.invoke()
                assertEquals(listOf("https://submit.invalid/article"), submissions)
            }
        } finally { host.main { activity.finish() } }
    }
}
