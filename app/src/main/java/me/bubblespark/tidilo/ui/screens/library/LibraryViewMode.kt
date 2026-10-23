package me.bubblespark.tidilo.ui.screens.library

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import me.bubblespark.tidilo.R

/**
 * Representation of bookmark list view modes available in LibraryScreen.
 */
enum class LibraryViewMode(
    @StringRes val labelRes: Int,
    @DrawableRes val iconRes: Int
) {
    LIST(R.string.library_view_mode_list, R.drawable.ic_list),
    GRID(R.string.library_view_mode_grid, R.drawable.ic_grid_view);

    val icon: ImageVector
        @Composable
        get() = ImageVector.vectorResource(iconRes)
}
