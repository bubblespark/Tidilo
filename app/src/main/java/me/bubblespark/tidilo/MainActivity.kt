package me.bubblespark.tidilo

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import me.bubblespark.tidilo.config.AppConfig
import me.bubblespark.tidilo.ui.navigation.LocalBarsVisible
import me.bubblespark.tidilo.ui.navigation.Screen
import me.bubblespark.tidilo.ui.navigation.TidiloBottomBar
import me.bubblespark.tidilo.ui.navigation.TidiloNavGraph
import me.bubblespark.tidilo.ui.screens.library.LibraryViewModel
import me.bubblespark.tidilo.ui.screens.queue.QueueViewModel
import me.bubblespark.tidilo.ui.theme.TidiloTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Enable Edge-to-Edge display recommended for Android 15+
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            TidiloTheme {
                TidiloApp()
            }
        }
    }
}

@Composable
fun TidiloApp(
    sharedUrl: String? = null,
    modifier: Modifier = Modifier,
    queueViewModel: QueueViewModel = viewModel(),
    libraryViewModel: LibraryViewModel = viewModel()
) {
    val navController = rememberNavController()
    val queuingItems by queueViewModel.queuingItems.collectAsStateWithLifecycle()
    val runningItems by queueViewModel.runningItems.collectAsStateWithLifecycle()
    val quotaExceededDialogVisible by queueViewModel.quotaExceededDialogVisible.collectAsStateWithLifecycle()
    val currentLanguage by AppConfig.currentLanguage.collectAsStateWithLifecycle()

    // When receiving a shared URL via Intent, enqueue to Queuing and navigate to Queue screen automatically
    LaunchedEffect(sharedUrl) {
        if (!sharedUrl.isNullOrBlank()) {
            queueViewModel.enqueueUrl(sharedUrl)
            navController.navigate(Screen.Queue.route) {
                launchSingleTop = true
            }
        }
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val isBottomBarVisible = Screen.allTabs.any { it.route == currentRoute }

    val filterMode by libraryViewModel.filterMode.collectAsStateWithLifecycle()
    val selectedDomainFilter by libraryViewModel.selectedDomainFilter.collectAsStateWithLifecycle()

    // Header and navigation bar visibility state
    var isBarsVisible by remember { mutableStateOf(true) }
    // Library FAB menu expanded/collapsed state
    var isFilterMenuExpanded by remember { mutableStateOf(false) }

    // Reset bars to visible and collapse FAB menu when switching tabs, filter modes, or domain drilldown
    LaunchedEffect(currentRoute, filterMode, selectedDomainFilter) {
        isBarsVisible = true
        isFilterMenuExpanded = false
    }

    // Scroll linking: Trigger only when content is actually scrolled (prevents false triggers on short screens)
    val nestedScrollConnection = remember(isBottomBarVisible, currentRoute, filterMode, selectedDomainFilter) {
        object : NestedScrollConnection {
            private var accumulatedScroll = 0f
            private val hideThreshold = 30f // Threshold for downward scroll to hide
            private val showThreshold = 20f // Threshold for upward scroll to show

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (!isBottomBarVisible) return Offset.Zero

                val delta = consumed.y
                if (delta < 0f) {
                    // Content scrolled down (finger swiped up)
                    if (accumulatedScroll > 0f) accumulatedScroll = 0f
                    accumulatedScroll += delta
                    if (accumulatedScroll < -hideThreshold) {
                        isBarsVisible = false
                        isFilterMenuExpanded = false
                        accumulatedScroll = 0f
                    }
                } else if (delta > 0f) {
                    // Content scrolled up (finger swiped down)
                    if (accumulatedScroll < 0f) accumulatedScroll = 0f
                    accumulatedScroll += delta
                    if (accumulatedScroll > showThreshold) {
                        isBarsVisible = true
                        accumulatedScroll = 0f
                    }
                } else if (available.y > showThreshold) {
                    // Restore visibility reliably upon overscroll pull-down at top
                    isBarsVisible = true
                    accumulatedScroll = 0f
                }
                return Offset.Zero
            }
        }
    }

    CompositionLocalProvider(LocalBarsVisible provides isBarsVisible) {
        Scaffold(
            modifier = modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets(0, 0, 0, 0)
        ) { _ ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(nestedScrollConnection)
            ) {
                // Full-screen content layer extending edge-to-edge
                TidiloNavGraph(
                    navController = navController,
                    queueViewModel = queueViewModel,
                    libraryViewModel = libraryViewModel,
                    sharedUrl = sharedUrl,
                    modifier = Modifier.fillMaxSize()
                )

                // Status bar protection gradient scrim when header is hidden
                AnimatedVisibility(
                    visible = isBottomBarVisible && !isBarsVisible,
                    enter = fadeIn(animationSpec = tween(200)),
                    exit = fadeOut(animationSpec = tween(150)),
                    modifier = Modifier.align(Alignment.TopCenter)
                ) {
                    val scrimBg = MaterialTheme.colorScheme.background
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 28.dp)
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        scrimBg.copy(alpha = 0.85f),
                                        scrimBg.copy(alpha = 0.50f),
                                        scrimBg.copy(alpha = 0.15f),
                                        Color.Transparent
                                    )
                                )
                            )
                    )
                }

                // Background dimming scrim when Library FAB menu is expanded
                AnimatedVisibility(
                    visible = isBottomBarVisible && isFilterMenuExpanded,
                    enter = fadeIn(animationSpec = tween(150)),
                    exit = fadeOut(animationSpec = tween(100))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.32f))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                isFilterMenuExpanded = false
                            }
                    )
                }

                // M3 Expressive navigation bar & action button shown only on main tabs (Library, Search, Queue)
                AnimatedVisibility(
                    visible = isBottomBarVisible,
                    enter = fadeIn(animationSpec = tween(200)),
                    exit = fadeOut(animationSpec = tween(150)),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 24.dp)
                ) {
                    TidiloBottomBar(
                        navController = navController,
                        isBarsVisible = isBarsVisible,
                        queuingCount = queuingItems.size,
                        isQueueRunning = runningItems.isNotEmpty(),
                        isFilterMenuExpanded = isFilterMenuExpanded,
                        onToggleFilterMenu = { isFilterMenuExpanded = !isFilterMenuExpanded },
                        currentFilterMode = filterMode,
                        onSelectFilterMode = { mode ->
                            libraryViewModel.setFilterMode(mode)
                        },
                        onSearchAction = {
                            libraryViewModel.triggerSearchFocus()
                        },
                        onAiTaggingAll = {
                            queueViewModel.aiTaggingAll()
                        }
                    )
                }
            }
        }

        if (quotaExceededDialogVisible) {
            AlertDialog(
                onDismissRequest = { queueViewModel.dismissQuotaExceededDialog() },
                title = {
                    Text(
                        text = "AICore",
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text(
                        text = if (currentLanguage.isJapanese) {
                            "AICore使用制限を超過しました。URLを取り込めません。しばらく待ってから再度実行するか、フォールバック機能の仕様を検討してください。"
                        } else {
                            "You have exceeded the AICore usage limit. We cannot import the URL. Please wait a while and try again, or enable the fallback feature."
                        }
                    )
                },
                confirmButton = {
                    TextButton(onClick = { queueViewModel.dismissQuotaExceededDialog() }) {
                        Text(text = "OK")
                    }
                }
            )
        }
    }
}
