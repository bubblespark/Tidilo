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

import androidx.annotation.StringRes
import me.bubblespark.tidilo.R

sealed class Screen(
    val route: String,
    val title: String,
    @StringRes val titleRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    data object Library : Screen(
        route = "library",
        title = "Library",
        titleRes = R.string.nav_library,
        selectedIcon = Icons.Filled.CollectionsBookmark,
        unselectedIcon = Icons.Outlined.CollectionsBookmark
    )

    data object Import : Screen(
        route = "import",
        title = "Import",
        titleRes = R.string.nav_import,
        selectedIcon = Icons.Filled.HourglassTop,
        unselectedIcon = Icons.Outlined.HourglassEmpty
    )

    data object Search : Screen(
        route = "search",
        title = "Search",
        titleRes = R.string.nav_search,
        selectedIcon = Icons.Filled.Search,
        unselectedIcon = Icons.Outlined.Search
    )

    data object RssSettings : Screen(
        route = "settings_rss", title = "RSS", titleRes = R.string.rss_title,
        selectedIcon = Icons.Filled.CollectionsBookmark, unselectedIcon = Icons.Outlined.CollectionsBookmark
    )

    data object Storage : Screen(
        route = "settings_storage",
        title = "Storage",
        titleRes = R.string.summary_title,
        selectedIcon = Icons.Filled.CollectionsBookmark,
        unselectedIcon = Icons.Outlined.CollectionsBookmark
    )

    data object SettingsDebug : Screen(
        route = "settings_debug",
        title = "Debug",
        titleRes = R.string.nav_debug,
        selectedIcon = Icons.Filled.BugReport,
        unselectedIcon = Icons.Outlined.BugReport
    )

    data object TagSelection : Screen(
        route = "tag_selection",
        title = "Tag Selection",
        titleRes = R.string.title_tag_selection,
        selectedIcon = Icons.Filled.CollectionsBookmark,
        unselectedIcon = Icons.Outlined.CollectionsBookmark
    )

    data object Settings : Screen(
        route = "settings",
        title = "Settings",
        titleRes = R.string.title_settings,
        selectedIcon = Icons.Filled.CollectionsBookmark,
        unselectedIcon = Icons.Outlined.CollectionsBookmark
    )

    data object AiAnalysis : Screen(
        route = "settings_ai_analysis",
        title = "AI Analysis",
        titleRes = R.string.settings_ai_analysis_title,
        selectedIcon = Icons.Filled.CollectionsBookmark,
        unselectedIcon = Icons.Outlined.CollectionsBookmark
    )

    data object GenerateContentRequest : Screen(
        route = "generate_content_request",
        title = "GenerateContentRequest",
        titleRes = R.string.title_generate_content_request,
        selectedIcon = Icons.Filled.CollectionsBookmark,
        unselectedIcon = Icons.Outlined.CollectionsBookmark
    )

    data object NsfwContents : Screen(
        route = "settings_nsfw",
        title = "NSFW Contents",
        titleRes = R.string.title_nsfw_contents,
        selectedIcon = Icons.Filled.CollectionsBookmark,
        unselectedIcon = Icons.Outlined.CollectionsBookmark
    )

    data object BookmarkDetail : Screen(
        route = "bookmark_detail/{bookmarkId}",
        title = "Details",
        titleRes = R.string.title_bookmark_detail,
        selectedIcon = Icons.Filled.CollectionsBookmark,
        unselectedIcon = Icons.Outlined.CollectionsBookmark
    ) {
        fun createRoute(bookmarkId: String): String = "bookmark_detail/$bookmarkId"
    }

    data object BookmarkAiReview : Screen(
        route = "bookmark_ai_review/{bookmarkId}",
        title = "AI Review",
        titleRes = R.string.title_bookmark_ai_review,
        selectedIcon = Icons.Filled.CollectionsBookmark,
        unselectedIcon = Icons.Outlined.CollectionsBookmark
    ) {
        fun createRoute(bookmarkId: String): String = "bookmark_ai_review/$bookmarkId"
    }

    companion object {
        val allTabs = listOf(Library, Search, Import)
        val Summary = Storage
    }
}
