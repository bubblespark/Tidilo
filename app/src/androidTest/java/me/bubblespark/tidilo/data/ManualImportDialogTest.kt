package me.bubblespark.tidilo.data

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.Context
import android.content.res.Configuration
import android.provider.Settings
import android.view.View
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.config.AppTheme
import me.bubblespark.tidilo.ui.screens.importscreen.ImportUrlDialog
import me.bubblespark.tidilo.ui.screens.importscreen.ManualImportState
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ManualImportDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext

    @Test
    fun pasteAndImeUseTheSameSubmissionGuardAsImport() {
        val submitted = mutableListOf<String>()
        val keyboardSetting = "show_ime_with_hard_keyboard"
        val resolver = context.contentResolver
        val originalKeyboardSetting = Settings.Secure.getString(resolver, keyboardSetting)
        fun setKeyboardSetting(value: String?) {
            val automation = instrumentation.uiAutomation
            automation.adoptShellPermissionIdentity(android.Manifest.permission.WRITE_SECURE_SETTINGS)
            try {
                check(Settings.Secure.putString(resolver, keyboardSetting, value))
            } finally {
                automation.dropShellPermissionIdentity()
            }
        }
        val clipboard = object : Clipboard {
            @Suppress("OVERRIDE_DEPRECATION")
            override val nativeClipboard get() = error("This test provides a Compose clipboard")
            override suspend fun getClipEntry() = ClipEntry(ClipData.newPlainText("Test URL", "paste.invalid:8443/path?q=1#top"))
            override suspend fun setClipEntry(clipEntry: ClipEntry?) = Unit
        }
        var hostView: View? = null
        try {
            setKeyboardSetting("1")
            composeRule.setContent {
                hostView = LocalView.current
                TidiloTheme(blurEnabled = false, dynamicColor = false) {
                    CompositionLocalProvider(LocalClipboard provides clipboard) {
                        ImportUrlDialog({}, { submitted += it })
                    }
                }
            }
            val pasteLabel = context.getString(R.string.import_dialog_paste)
            composeRule.waitUntil(5000) {
                composeRule.onAllNodes(hasText(pasteLabel), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText(pasteLabel, useUnmergedTree = true).performClick()

            val url = "https://paste.invalid:8443/path?q=1#top"
            val resolvedText = context.getString(R.string.import_dialog_resolved_url, url)
            composeRule.waitUntil(5000) {
                composeRule.onAllNodes(hasText(resolvedText), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }

            val imeInputMatcher = SemanticsMatcher("IME action input") {
                it.config.getOrNull(SemanticsActions.OnImeAction) != null
            }
            composeRule.onNode(imeInputMatcher, useUnmergedTree = true).performClick()
            composeRule.waitUntil(5000) {
                val view = hostView ?: return@waitUntil false
                ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true
            }

            val confirmLabel = context.getString(R.string.import_dialog_confirm)
            val inputNode = composeRule.onNode(imeInputMatcher, useUnmergedTree = true).fetchSemanticsNode()
            val confirmNode = composeRule.onNodeWithText(confirmLabel, useUnmergedTree = true).fetchSemanticsNode()
            assertTrue(inputNode.boundsInWindow.height > 0)
            assertTrue(confirmNode.boundsInWindow.height > 0)

            composeRule.onNode(imeInputMatcher, useUnmergedTree = true).performImeAction()
            composeRule.runOnUiThread {
                val confirmButton = generateSequence(confirmNode) { it.parent }
                    .first { it.config.getOrNull(SemanticsActions.OnClick) != null }
                confirmButton.config[SemanticsActions.OnClick].action?.invoke()
            }
            composeRule.waitForIdle()
            assertEquals(listOf(url), submitted)
        } finally {
            setKeyboardSetting(originalKeyboardSetting)
        }
    }

    @Test
    fun draftSurvivesSavedStateRestoration() {
        var showDialog by mutableStateOf(true)
        var registry by mutableStateOf(SaveableStateRegistry(null) { true })
        composeRule.setContent {
            TidiloTheme(blurEnabled = false, dynamicColor = false) {
                if (showDialog) {
                    CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                        ImportUrlDialog({}, {})
                    }
                }
            }
        }
        composeRule.waitUntil(5000) {
            composeRule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(hasSetTextAction(), useUnmergedTree = true)
            .performTextReplacement("https://draft.invalid/article")
        composeRule.waitUntil(5000) {
            composeRule.onAllNodes(SemanticsMatcher("draft text") {
                it.config.getOrNull(SemanticsProperties.EditableText)?.text == "https://draft.invalid/article"
            }, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }

        var saved: Map<String, List<Any?>> = emptyMap()
        composeRule.runOnUiThread {
            saved = registry.performSave()
            showDialog = false
        }
        composeRule.waitForIdle()

        composeRule.runOnUiThread {
            registry = SaveableStateRegistry(saved) { true }
            showDialog = true
        }
        composeRule.waitForIdle()
        composeRule.waitUntil(5000) {
            composeRule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        val restored = composeRule.onNode(hasSetTextAction(), useUnmergedTree = true).fetchSemanticsNode()
        assertEquals(
            "Recreated dialog must retain the URL draft",
            "https://draft.invalid/article",
            restored.config.getOrNull(SemanticsProperties.EditableText)?.text
        )
    }

    @Test
    fun confirmationSubmitsUrlOnce() {
        val submissions = mutableListOf<String>()
        composeRule.setContent {
            TidiloTheme(blurEnabled = false, dynamicColor = false) {
                ImportUrlDialog({}, { submissions += it })
            }
        }
        composeRule.waitUntil(5000) {
            composeRule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(hasSetTextAction(), useUnmergedTree = true)
            .performTextReplacement("ftp://submit.invalid/article")
        val invalidError = context.getString(R.string.import_dialog_error_invalid)
        composeRule.waitUntil(5000) {
            composeRule.onAllNodes(hasText(invalidError), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNode(hasSetTextAction(), useUnmergedTree = true)
            .performTextReplacement("submit.invalid/article")
        val resolvedText = context.getString(R.string.import_dialog_resolved_url, "https://submit.invalid/article")
        composeRule.waitUntil(5000) {
            composeRule.onAllNodes(hasText(resolvedText), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }

        val confirmLabel = context.getString(R.string.import_dialog_confirm)
        val enabledConfirm = SemanticsMatcher("enabled confirm") { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == confirmLabel } == true &&
                generateSequence(node) { it.parent }
                    .firstOrNull { it.config.getOrNull(SemanticsActions.OnClick) != null }
                    ?.config?.contains(SemanticsProperties.Disabled) == false
        }
        composeRule.waitUntil(5000) {
            composeRule.onAllNodes(enabledConfirm, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        val confirmNode = composeRule.onNode(enabledConfirm, useUnmergedTree = true).fetchSemanticsNode()
        composeRule.runOnUiThread {
            val target = generateSequence(confirmNode) { it.parent }
                .first { it.config.getOrNull(SemanticsActions.OnClick) != null }
            target.config[SemanticsActions.OnClick].action?.invoke()
            target.config[SemanticsActions.OnClick].action?.invoke()
        }
        composeRule.waitForIdle()
        assertEquals(listOf("https://submit.invalid/article"), submissions)
    }

    @Test
    fun savingExposesIndeterminateProgressAndBlocksBackInBothThemes() {
        var theme by mutableStateOf(AppTheme.DEFAULT)
        var locale by mutableStateOf(Locale.ENGLISH)
        var dismissals = 0
        var state by mutableStateOf<ManualImportState>(ManualImportState.Saving)
        val url = "https://saved.invalid/article"

        composeRule.setContent {
            val baseContext = LocalContext.current
            val baseConfiguration = LocalConfiguration.current
            val configuration = remember(locale, baseConfiguration) {
                Configuration(baseConfiguration).apply { setLocale(locale) }
            }
            val localized = remember(configuration) { baseContext.createConfigurationContext(configuration) }
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides configuration,
                LocalResources provides localized.resources
            ) {
                TidiloTheme(theme = theme, blurEnabled = false, dynamicColor = false) {
                    ImportUrlDialog(onDismiss = { dismissals++ }, onConfirm = {}, state = state)
                }
            }
        }

        for ((preset, language, label) in listOf(
            Triple(AppTheme.DEFAULT, Locale.ENGLISH, "Saving page…"),
            Triple(AppTheme.MODERN, Locale.JAPANESE, "ページを保存しています…")
        )) {
            composeRule.runOnUiThread {
                theme = preset
                locale = language
            }
            composeRule.waitForIdle()
            val progressMatcher = SemanticsMatcher("indeterminate progress") {
                it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo) == ProgressBarRangeInfo.Indeterminate
            }
            composeRule.waitUntil(5000) {
                composeRule.onAllNodes(progressMatcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() &&
                    composeRule.onAllNodes(hasText(label), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            composeRule.waitForIdle()
            assertEquals(0, dismissals)
            assertTrue(composeRule.onAllNodes(hasSetTextAction(), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        }

        composeRule.runOnUiThread { state = ManualImportState.Finished(ImportOutcome.SavedDuplicate("bookmark-id", url)) }
        composeRule.waitUntil(5000) {
            composeRule.onAllNodes(hasText("保存済みのページを開く"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
