package me.bubblespark.tidilo.data

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
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.bubblespark.tidilo.config.AppConfig
import me.bubblespark.tidilo.config.AppTheme
import me.bubblespark.tidilo.ui.theme.TidiloTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Exercises the real preference flow and theme boundary with framework-owned UI state. */
class ThemeSwitchLifecycleTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun themeSwitchPreservesNavigationScrollAndDraft() {
        val previous = AppConfig.currentTheme.value
        lateinit var nav: NavHostController
        lateinit var list: LazyListState
        lateinit var scope: CoroutineScope
        var observedDraft = ""
        try {
            composeRule.setContent {
                val theme by AppConfig.currentTheme.collectAsStateWithLifecycle()
                TidiloTheme(darkTheme = false, theme = theme, dynamicColor = false, blurEnabled = false) {
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
            composeRule.awaitNode(hasSetTextAction())
            composeRule.onNode(hasSetTextAction()).performTextReplacement("Retained draft")
            composeRule.awaitNode(hasText("Retained draft"))
            assertEquals("The editor must commit the draft before navigation", "Retained draft", observedDraft)
            composeRule.runOnUiThread { scope.launch { list.scrollToItem(12) } }
            composeRule.awaitNode(hasText("Row 12"))
            val originalController = nav
            composeRule.runOnUiThread { nav.navigate("second") }
            composeRule.awaitNode(hasText("Second route"))
            for (theme in listOf(AppTheme.MODERN, AppTheme.DEFAULT, AppTheme.MODERN)) {
                composeRule.runOnUiThread { AppConfig.setTheme(theme) }
                composeRule.awaitNode(hasText("Theme ${theme.code}"))
                composeRule.runOnUiThread {
                    assertSame(originalController, nav)
                    assertEquals("second", nav.currentDestination?.route)
                    assertEquals(12, list.firstVisibleItemIndex)
                    assertEquals("Retained draft", observedDraft)
                }
            }
            composeRule.runOnUiThread { nav.popBackStack() }
            composeRule.awaitNode(hasText("Row 12"))
            assertEquals("Retained draft", observedDraft)
        } finally {
            composeRule.runOnUiThread { AppConfig.setTheme(previous) }
        }
    }
}
