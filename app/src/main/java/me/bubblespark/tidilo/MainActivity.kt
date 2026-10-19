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
import me.bubblespark.tidilo.ui.screens.importscreen.ImportViewModel
import me.bubblespark.tidilo.ui.theme.TidiloTheme

import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import java.util.Locale
import me.bubblespark.tidilo.config.AppLanguage

class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: android.content.Context) {
        val prefs = newBase.getSharedPreferences("tidilo_app_config_prefs", android.content.Context.MODE_PRIVATE)
        val langCode = prefs.getString("app_language", AppLanguage.ENGLISH.code) ?: AppLanguage.ENGLISH.code
        val locale = Locale.forLanguageTag(langCode)
        val config = Configuration(newBase.resources.configuration).apply {
            setLocale(locale)
            setLayoutDirection(locale)
        }
        val localizedContext = newBase.createConfigurationContext(config)
        super.attachBaseContext(localizedContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Enable Edge-to-Edge display recommended for Android 15+
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Ensure Activity resources are initialized with the configured language
        val initialLang = AppConfig.currentLanguage.value
        val initialLocale = Locale.forLanguageTag(initialLang.code)
        val initialConfig = Configuration(resources.configuration).apply {
            setLocale(initialLocale)
            setLayoutDirection(initialLocale)
        }
        @Suppress("DEPRECATION")
        resources.updateConfiguration(initialConfig, resources.displayMetrics)

        setContent {
            val currentLanguage by AppConfig.currentLanguage.collectAsStateWithLifecycle()
            val locale = remember(currentLanguage) { Locale.forLanguageTag(currentLanguage.code) }
            val configuration = LocalConfiguration.current
            val updatedConfig = remember(currentLanguage, configuration) {
                Configuration(configuration).apply {
                    setLocale(locale)
                    setLayoutDirection(locale)
                }
            }
            val context = LocalContext.current
            val localizedContext = remember(currentLanguage, context, updatedConfig) {
                context.createConfigurationContext(updatedConfig)
            }

            // Keep Activity resources and Application resources synchronized so that
            // Dialogs and Popups (which create new Windows) load the correct localized strings
            SideEffect {
                @Suppress("DEPRECATION")
                resources.updateConfiguration(updatedConfig, resources.displayMetrics)
                @Suppress("DEPRECATION")
                applicationContext.resources.updateConfiguration(updatedConfig, applicationContext.resources.displayMetrics)
            }

            CompositionLocalProvider(
                LocalConfiguration provides updatedConfig,
                LocalContext provides localizedContext
            ) {
                TidiloTheme {
                    TidiloApp()
                }
            }
        }
    }
}

@Composable
fun TidiloApp(
    sharedUrl: String? = null,
    modifier: Modifier = Modifier,
    importViewModel: ImportViewModel = viewModel(),
    libraryViewModel: LibraryViewModel = viewModel()
) {
    val navController = rememberNavController()
    val readyTasks by importViewModel.readyTasks.collectAsStateWithLifecycle()
    val processingTasks by importViewModel.processingTasks.collectAsStateWithLifecycle()
    val aiProcessingProgress by importViewModel.aiProcessingProgress.collectAsStateWithLifecycle()
    val quotaExceededDialogVisible by importViewModel.quotaExceededDialogVisible.collectAsStateWithLifecycle()
    val currentLanguage by AppConfig.currentLanguage.collectAsStateWithLifecycle()

    // When receiving a shared URL via Intent, import to Ready and navigate to Import screen automatically
    LaunchedEffect(sharedUrl) {
        if (!sharedUrl.isNullOrBlank()) {
            importViewModel.importUrl(sharedUrl)
            navController.navigate(Screen.Import.route) {
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
    // AI Processing speech bubble visible state
    var isAiSpeechBubbleVisible by remember { mutableStateOf(false) }

    // Reset bars to visible and collapse menus when switching tabs, filter modes, or domain drilldown
    LaunchedEffect(currentRoute, filterMode, selectedDomainFilter) {
        isBarsVisible = true
        isFilterMenuExpanded = false
        isAiSpeechBubbleVisible = false
    }

    // Dismiss AI speech bubble when processing completes
    LaunchedEffect(aiProcessingProgress.isProcessing) {
        if (!aiProcessingProgress.isProcessing) {
            isAiSpeechBubbleVisible = false
        }
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
                    importViewModel = importViewModel,
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

                // Invisible dismiss scrim when AI speech bubble is open (tap outside to close)
                if (isBottomBarVisible && isAiSpeechBubbleVisible && aiProcessingProgress.isProcessing) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                isAiSpeechBubbleVisible = false
                            }
                    )
                }

                // M3 Expressive navigation bar & action button shown only on main tabs (Library, Search, Import)
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
                        readyCount = readyTasks.size,
                        isProcessing = processingTasks.isNotEmpty(),
                        isFilterMenuExpanded = isFilterMenuExpanded,
                        onToggleFilterMenu = {
                            isFilterMenuExpanded = !isFilterMenuExpanded
                            if (isFilterMenuExpanded) isAiSpeechBubbleVisible = false
                        },
                        currentFilterMode = filterMode,
                        onSelectFilterMode = { mode ->
                            libraryViewModel.setFilterMode(mode)
                        },
                        onSearchAction = {
                            libraryViewModel.triggerSearchFocus()
                        },
                        onAiAnalysisAll = {
                            importViewModel.aiAnalysisAll()
                        },
                        aiProcessingProgress = aiProcessingProgress,
                        isSpeechBubbleVisible = isAiSpeechBubbleVisible,
                        onToggleSpeechBubble = {
                            isAiSpeechBubbleVisible = !isAiSpeechBubbleVisible
                            if (isAiSpeechBubbleVisible) isFilterMenuExpanded = false
                        },
                        onDismissSpeechBubble = { isAiSpeechBubbleVisible = false },
                        onNavigateToImport = {
                            if (currentRoute != Screen.Import.route) {
                                navController.navigate(Screen.Import.route) {
                                    launchSingleTop = true
                                }
                            }
                        }
                    )
                }
            }
        }

        if (quotaExceededDialogVisible) {
            AlertDialog(
                onDismissRequest = { importViewModel.dismissQuotaExceededDialog() },
                title = {
                    Text(
                        text = stringResource(R.string.dialog_aicore_title),
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text(
                        text = stringResource(R.string.dialog_aicore_quota_exceeded_message)
                    )
                },
                confirmButton = {
                    TextButton(onClick = { importViewModel.dismissQuotaExceededDialog() }) {
                        Text(text = stringResource(R.string.common_ok))
                    }
                }
            )
        }
    }
}
