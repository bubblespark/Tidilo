package me.bubblespark.tidilo.data

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.bubblespark.tidilo.config.AppConfig
import me.bubblespark.tidilo.config.AppTheme
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.*
import org.junit.Test

/** Exercises the real preference flow and theme boundary with framework-owned UI state. */
class ThemeSwitchLifecycleTest {
    @Test fun themeSwitchPreservesNavigationScrollAndDraft() = runBlocking {
        val host = HeaderTestHost()
        val activity = host.launch()
        val previous = AppConfig.currentTheme.value
        lateinit var nav: NavHostController
        lateinit var list: LazyListState
        lateinit var scope: CoroutineScope
        var observedDraft = ""
        try {
            host.main {
                activity.setContent {
                    val theme by AppConfig.currentTheme.collectAsStateWithLifecycle()
                    TidiloTheme(theme = theme, dynamicColor = false) {
                        val controller = rememberNavController()
                        val state = rememberLazyListState()
                        val coroutineScope = rememberCoroutineScope()
                        var draft by rememberSaveable { mutableStateOf("Draft") }
                        val committedDraft = draft
                        SideEffect { nav = controller; list = state; scope = coroutineScope; observedDraft = committedDraft }
                        Column {
                            Text("Theme ${theme.code}")
                            NavHost(controller, startDestination = "editor") {
                                composable("editor") {
                                    Column {
                                        BasicTextField(draft, onValueChange = { draft = it })
                                        LazyColumn(state = state, modifier = Modifier.height(150.dp)) {
                                            items((0..49).toList(), key = { it }) { Text("Row $it", Modifier.height(40.dp)) }
                                        }
                                    }
                                }
                                composable("second") { Text("Second route") }
                            }
                        }
                    }
                }
            }
            val field = host.await(activity) { it.config.getOrNull(SemanticsActions.SetText) != null }
            host.main { field.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("Retained draft")) }
            host.await(activity) {
                it.config.getOrNull(SemanticsProperties.EditableText)?.text == "Retained draft"
            }
            host.main { assertEquals("The editor must commit the draft before navigation", "Retained draft", observedDraft) }
            host.main { scope.launch { list.scrollToItem(12) } }
            host.await(activity) { host.text(it, "Row 12") }
            val originalController = nav
            host.main { nav.navigate("second") }
            host.await(activity) { host.text(it, "Second route") }
            for (theme in listOf(AppTheme.MODERN, AppTheme.DEFAULT, AppTheme.MODERN)) {
                host.main { AppConfig.setTheme(theme) }
                host.await(activity) { host.text(it, "Theme ${theme.code}") }
                host.main {
                    assertSame(originalController, nav)
                    assertEquals("second", nav.currentDestination?.route)
                    assertEquals(12, list.firstVisibleItemIndex)
                    assertEquals("Retained draft", observedDraft)
                }
            }
            host.main { nav.popBackStack() }
            host.await(activity) { host.text(it, "Row 12") }
            host.main { assertEquals("Retained draft", observedDraft) }
        } finally {
            host.main { AppConfig.setTheme(previous); activity.finish() }
        }
    }

    @Test fun themeSelectionPersistsWithStableCode() {
        val host = HeaderTestHost()
        val previous = AppConfig.currentTheme.value
        try {
            host.main { AppConfig.setTheme(AppTheme.MODERN) }
            val prefs = host.context.getSharedPreferences("tidilo_app_config_prefs", android.content.Context.MODE_PRIVATE)
            assertEquals(AppTheme.MODERN.code, prefs.getString("app_theme", null))
            assertEquals(AppTheme.DEFAULT, AppTheme.fromCode("unknown-theme"))
        } finally { host.main { AppConfig.setTheme(previous) } }
    }
}
