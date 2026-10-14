package me.bubblespark.tidilo.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Search
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(
    val route: String,
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    data object Library : Screen(
        route = "library",
        title = "Library",
        selectedIcon = Icons.Filled.CollectionsBookmark,
        unselectedIcon = Icons.Outlined.CollectionsBookmark
    )

    data object Queue : Screen(
        route = "queue",
        title = "Queue",
        selectedIcon = Icons.Filled.HourglassTop,
        unselectedIcon = Icons.Outlined.HourglassEmpty
    )

    data object Search : Screen(
        route = "search",
        title = "Search",
        selectedIcon = Icons.Filled.Search,
        unselectedIcon = Icons.Outlined.Search
    )

    data object SettingsDebug : Screen(
        route = "settings_debug",
        title = "Debug",
        selectedIcon = Icons.Filled.BugReport,
        unselectedIcon = Icons.Outlined.BugReport
    )

    data object TagSelection : Screen(
        route = "tag_selection",
        title = "Tag Selection",
        selectedIcon = Icons.Filled.CollectionsBookmark,
        unselectedIcon = Icons.Outlined.CollectionsBookmark
    )

    data object Settings : Screen(
        route = "settings",
        title = "Settings",
        selectedIcon = Icons.Filled.CollectionsBookmark,
        unselectedIcon = Icons.Outlined.CollectionsBookmark
    )

    data object GenerateContentRequest : Screen(
        route = "generate_content_request",
        title = "GenerateContentRequest",
        selectedIcon = Icons.Filled.CollectionsBookmark,
        unselectedIcon = Icons.Outlined.CollectionsBookmark
    )

    data object NsfwContents : Screen(
        route = "settings_nsfw",
        title = "NSFW Contents",
        selectedIcon = Icons.Filled.CollectionsBookmark,
        unselectedIcon = Icons.Outlined.CollectionsBookmark
    )

    data object BookmarkDetail : Screen(
        route = "bookmark_detail/{bookmarkId}",
        title = "Details",
        selectedIcon = Icons.Filled.CollectionsBookmark,
        unselectedIcon = Icons.Outlined.CollectionsBookmark
    ) {
        fun createRoute(bookmarkId: String): String = "bookmark_detail/$bookmarkId"
    }

    data object BookmarkAiReview : Screen(
        route = "bookmark_ai_review/{bookmarkId}",
        title = "AI Review",
        selectedIcon = Icons.Filled.CollectionsBookmark,
        unselectedIcon = Icons.Outlined.CollectionsBookmark
    ) {
        fun createRoute(bookmarkId: String): String = "bookmark_ai_review/$bookmarkId"
    }

    companion object {
        val allTabs = listOf(Library, Search, Queue)
    }
}
