package me.bubblespark.tidilo

import android.content.Intent
import kotlinx.coroutines.launch
import me.bubblespark.tidilo.data.notifications.NotificationCoordinator
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import me.bubblespark.tidilo.config.AppConfig
import me.bubblespark.tidilo.data.ImportOutcome
import me.bubblespark.tidilo.ui.components.AmbientLightingState
import me.bubblespark.tidilo.ui.components.LocalAmbientLighting
import me.bubblespark.tidilo.ui.components.StatusBarScrim
import me.bubblespark.tidilo.ui.components.TidiloBackground
import me.bubblespark.tidilo.ui.navigation.LocalBarsVisible
import me.bubblespark.tidilo.ui.navigation.Screen
import me.bubblespark.tidilo.ui.navigation.LibraryNavigationRail
import me.bubblespark.tidilo.ui.navigation.TidiloFloatingNavigation
import me.bubblespark.tidilo.ui.navigation.TopLevelDestination
import me.bubblespark.tidilo.ui.navigation.navigateTopLevel
import me.bubblespark.tidilo.ui.navigation.normalizeNotificationDestination
import me.bubblespark.tidilo.ui.navigation.topLevelDestinationOf
import me.bubblespark.tidilo.ui.screens.importscreen.ImportUrlDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.WideNavigationRailValue
import androidx.compose.material3.rememberWideNavigationRailState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import me.bubblespark.tidilo.ui.navigation.TidiloNavGraph
import me.bubblespark.tidilo.ui.screens.library.LibraryViewModel
import me.bubblespark.tidilo.ui.screens.importscreen.ImportViewModel
import me.bubblespark.tidilo.ui.theme.TidiloTheme

import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import java.util.Locale
import me.bubblespark.tidilo.config.AppLanguage

class MainActivity : ComponentActivity() {
    private val notificationDestination = mutableStateOf<String?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val destination = notificationRoute(intent) ?: return
        setIntent(intent)
        notificationDestination.value = destination
    }

    private fun notificationRoute(intent: Intent?): String? =
        normalizeNotificationDestination(intent?.getStringExtra(NotificationCoordinator.EXTRA_DESTINATION))

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
        notificationDestination.value = notificationRoute(intent)

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
                LocalContext provides localizedContext,
                LocalActivityResultRegistryOwner provides this@MainActivity
            ) {
                val selectedTheme by AppConfig.currentTheme.collectAsStateWithLifecycle()
                val blurEnabled by AppConfig.overlayBlurEnabled.collectAsStateWithLifecycle()
                TidiloTheme(theme = selectedTheme, blurEnabled = blurEnabled) {
                    TidiloApp(notificationRoute = notificationDestination.value, onNotificationConsumed = {
                        notificationDestination.value = null
                        intent.removeExtra(NotificationCoordinator.EXTRA_DESTINATION)
                    })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TidiloApp(
    sharedUrl: String? = null,
    modifier: Modifier = Modifier,
    importViewModel: ImportViewModel = viewModel(),
    libraryViewModel: LibraryViewModel = viewModel(),
    notificationRoute: String? = null,
    onNotificationConsumed: () -> Unit = {},
    discoverPreviewEnabled: Boolean = remember { AppConfig.discoverPreviewEnabled.value }
) {
    val navController = rememberNavController()
    val railState = rememberWideNavigationRailState()
    val navigationScope = rememberCoroutineScope()
    var showManualImport by rememberSaveable { mutableStateOf(false) }
    val manualImportState by importViewModel.manualImportState.collectAsStateWithLifecycle()
    var notificationDeferred by rememberSaveable { mutableStateOf(false) }
    var consumedSharedUrl by rememberSaveable { mutableStateOf<String?>(null) }
    fun openDestination(route: String) {
        navigationScope.launch {
            railState.collapse()
            val current = navController.currentDestination?.route
            val canNavigate = if (discoverPreviewEnabled) {
                topLevelDestinationOf(current) != null
            } else {
                current == Screen.Library.route
            }
            if (canNavigate) {
                navController.navigate(route) { launchSingleTop = true }
            }
        }
    }
    val currentLanguage by AppConfig.currentLanguage.collectAsStateWithLifecycle()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val isLibraryRoute = currentRoute == Screen.Library.route
    val topLevel = topLevelDestinationOf(currentRoute)
    val isTopLevelChromeRoute = if (discoverPreviewEnabled) topLevel != null else isLibraryRoute
    val isRailRoute = if (discoverPreviewEnabled) {
        currentRoute == null || topLevel == TopLevelDestination.DISCOVER || topLevel == TopLevelDestination.LIBRARY
    } else {
        currentRoute == null || isLibraryRoute
    }
    val imeVisible = WindowInsets.isImeVisible

    LaunchedEffect(notificationRoute, manualImportState, notificationDeferred, currentRoute) {
        if (notificationRoute != null) {
            // The graph is set by the subcomposed NavHost; wait for its first entry before navigating.
            if (currentRoute == null) return@LaunchedEffect
            if (manualImportState != null) {
                notificationDeferred = true
                return@LaunchedEffect
            }
            val current = navController.currentDestination?.route
            val readyForNotification = if (discoverPreviewEnabled) {
                topLevelDestinationOf(current) != null
            } else {
                current == Screen.Library.route
            }
            if (notificationDeferred && !readyForNotification) {
                return@LaunchedEffect
            }
            showManualImport = false
            normalizeNotificationDestination(notificationRoute)?.let { destination -> navController.navigate(destination) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
            } }
            notificationDeferred = false
            onNotificationConsumed()
        }
    }

    // Consume the optional foreground share once across recreation; the share receiver remains independent.
    LaunchedEffect(sharedUrl, manualImportState) {
        if (manualImportState == null && !sharedUrl.isNullOrBlank() && sharedUrl != consumedSharedUrl) {
            consumedSharedUrl = sharedUrl
            importViewModel.importUrl(sharedUrl)
            if (discoverPreviewEnabled) {
                navController.navigateTopLevel(TopLevelDestination.LIBRARY)
            } else {
                navController.navigate(Screen.Library.route) {
                    popUpTo(Screen.Library.route) { inclusive = false }
                    launchSingleTop = true
                }
            }
        }
    }

    val filterMode by libraryViewModel.filterMode.collectAsStateWithLifecycle()
    val selectedDomainFilter by libraryViewModel.selectedDomainFilter.collectAsStateWithLifecycle()
    val selectedRssFeedFilter by libraryViewModel.selectedRssFeedFilter.collectAsStateWithLifecycle()
    val selectedCollectionId by libraryViewModel.selectedCollectionId.collectAsStateWithLifecycle()

    // Scroll visibility belongs to the Library header (and floating toolbar in preview mode); the radial trigger stays fixed.
    var isBarsVisible by remember { mutableStateOf(true) }
    // Restore chrome on navigation and filter changes.
    LaunchedEffect(currentRoute, filterMode, selectedDomainFilter, selectedRssFeedFilter?.id) {
        isBarsVisible = true
        if (currentRoute != null && !isRailRoute) railState.collapse()
    }

    // Scroll linking: Trigger only when content is actually scrolled (prevents false triggers on short screens)
    val nestedScrollConnection = remember(isTopLevelChromeRoute, currentRoute, filterMode, selectedDomainFilter, selectedRssFeedFilter?.id) {
        object : NestedScrollConnection {
            private var accumulatedScroll = 0f
            private val hideThreshold = 30f // Threshold for downward scroll to hide
            private val showThreshold = 20f // Threshold for upward scroll to show

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (!isTopLevelChromeRoute) return Offset.Zero

                val delta = consumed.y
                if (delta < 0f) {
                    // Content scrolled down (finger swiped up)
                    if (accumulatedScroll > 0f) accumulatedScroll = 0f
                    accumulatedScroll += delta
                    if (accumulatedScroll < -hideThreshold) {
                        isBarsVisible = false
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

    val ambientLightingState = remember { AmbientLightingState() }
    val isLibraryDrillDown = when (filterMode) {
        me.bubblespark.tidilo.ui.screens.library.LibraryFilterMode.COLLECTION -> selectedCollectionId != null
        me.bubblespark.tidilo.ui.screens.library.LibraryFilterMode.RSS -> selectedRssFeedFilter != null
        me.bubblespark.tidilo.ui.screens.library.LibraryFilterMode.DOMAIN -> selectedDomainFilter != null
        me.bubblespark.tidilo.ui.screens.library.LibraryFilterMode.NEWEST_FIRST -> false
    }
    // Discover (preview flag only) lights the background from the shelf in view.
    val isAmbientRoute = (currentRoute == Screen.Library.route && isLibraryDrillDown) || currentRoute == Screen.Discover.route
    SideEffect {
        ambientLightingState.isDestinationAllowed = isAmbientRoute
    }

    CompositionLocalProvider(
        LocalBarsVisible provides isBarsVisible,
        LocalAmbientLighting provides ambientLightingState
    ) {
        Scaffold(
            modifier = modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onBackground,
            contentWindowInsets = WindowInsets(0, 0, 0, 0)
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .nestedScroll(nestedScrollConnection)
            ) {
                // Stationary centralized background layer
                TidiloBackground(
                    modifier = Modifier.fillMaxSize(),
                    isDestinationAllowed = isAmbientRoute
                )

                // Full-screen content layer extending edge-to-edge
                TidiloNavGraph(
                    navController = navController,
                    importViewModel = importViewModel,
                    libraryViewModel = libraryViewModel,
                    modifier = Modifier.fillMaxSize(),
                    discoverPreviewEnabled = discoverPreviewEnabled,
                    onOpenNavigation = { navigationScope.launch {
                        androidx.compose.runtime.withFrameNanos { }
                        railState.expand()
                    } },
                    onOpenManualImport = { showManualImport = true }
                )

                // Status bar protection gradient scrim when header is hidden
                StatusBarScrim(
                    visible = isLibraryRoute && !isBarsVisible,
                    modifier = Modifier.align(Alignment.TopCenter)
                )

                if (discoverPreviewEnabled && topLevel != null) {
                    TidiloFloatingNavigation(
                        selected = topLevel,
                        visible = isBarsVisible && !imeVisible && railState.targetValue != WideNavigationRailValue.Expanded,
                        onSelect = { destination ->
                            if (destination == TopLevelDestination.SEARCH) {
                                libraryViewModel.triggerSearchFocus()
                            }
                            navController.navigateTopLevel(destination)
                        },
                        onSave = { showManualImport = true },
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                }

                if (isRailRoute) {
                    LibraryNavigationRail(railState, filterMode,
                        onSelectFilter = { mode ->
                            libraryViewModel.setFilterMode(mode)
                            navigationScope.launch {
                                railState.collapse()
                                if (discoverPreviewEnabled && currentRoute == Screen.Discover.route) {
                                    navController.navigateTopLevel(TopLevelDestination.LIBRARY)
                                }
                            }
                        },
                        onSearch = {
                            libraryViewModel.triggerSearchFocus()
                            openDestination(Screen.Search.route)
                        },
                        onNotifications = { openDestination(Screen.Notifications.route) },
                        onManualImport = { navigationScope.launch { railState.collapse(); showManualImport = true } },
                        onSettings = { openDestination(Screen.Settings.route) },
                        onTrash = { openDestination(Screen.Trash.route) },
                        trimmed = discoverPreviewEnabled)
                }

            }
        }

        if (isTopLevelChromeRoute && (showManualImport || manualImportState != null)) ImportUrlDialog(
            state = manualImportState,
            onDismiss = { if (importViewModel.dismissManualImport()) showManualImport = false },
            onConfirm = importViewModel::submitManualImport,
            onRetry = importViewModel::retryManualImport,
            onOpenSaved = {
                val bookmarkId = when (val result = importViewModel.consumeManualImportResult()) {
                    is ImportOutcome.BookmarkSaved -> result.bookmarkId
                    is ImportOutcome.SavedDuplicate -> result.bookmarkId
                    else -> null
                }
                if (bookmarkId != null) {
                    if (notificationRoute != null) notificationDeferred = true
                    showManualImport = false
                    navController.navigate(Screen.BookmarkDetail.createRoute(bookmarkId)) { launchSingleTop = true }
                }
            }
        )

    }
}
