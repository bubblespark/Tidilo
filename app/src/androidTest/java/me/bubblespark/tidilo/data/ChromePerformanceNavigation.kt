package me.bubblespark.tidilo.data

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import android.os.SystemClock
import android.view.MotionEvent
import android.view.InputDevice
import kotlinx.coroutines.delay
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull

@Composable
internal fun ChromePerformanceNavigation(detail: Boolean, library: @Composable () -> Unit, bookmarkDetail: @Composable () -> Unit) {
    val controller = rememberNavController()
    LaunchedEffect(detail) {
        if (detail && controller.currentDestination?.route != "detail") controller.navigate("detail") { launchSingleTop = true }
        else if (!detail) controller.popBackStack("library", false)
    }
    // Match TidiloNavGraph's native NavHost spring slides, including overlapping destinations.
    NavHost(controller, startDestination = "library",
        enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start,
            spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)) },
        exitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Start,
            spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)) },
        popEnterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.End,
            spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)) },
        popExitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End,
            spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)) }
    ) {
        composable("library") { library() }
        composable("detail") { bookmarkDetail() }
    }
}

internal suspend fun HeaderTestHost.chromeTap(node: SemanticsNode) {
    var x = 0f
    var y = 0f
    main {
        var clickable = node
        while (clickable.config.getOrNull(SemanticsActions.OnClick) == null && clickable.parent != null) clickable = requireNotNull(clickable.parent)
        x = clickable.boundsInWindow.center.x
        y = clickable.boundsInWindow.center.y
    }
    val downTime = SystemClock.uptimeMillis()
    fun send(action: Int) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        try { check(instrumentation.uiAutomation.injectInputEvent(event, true)) }
        finally { event.recycle() }
    }
    send(MotionEvent.ACTION_DOWN)
    try { delay(40) } finally { send(MotionEvent.ACTION_UP) }
}
