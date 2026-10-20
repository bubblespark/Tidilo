package me.bubblespark.tidilo.ui.screens.library

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.ui.components.GridViewSymbolIcon
import me.bubblespark.tidilo.ui.components.ListSymbolIcon

/**
 * Representation of bookmark list view modes available in LibraryScreen.
 */
enum class LibraryViewMode(
    @StringRes val labelRes: Int
) {
    LIST(R.string.library_view_mode_list) {
        override val icon: ImageVector
            get() = ListSymbolIcon
    },
    GRID(R.string.library_view_mode_grid) {
        override val icon: ImageVector
            get() = GridViewSymbolIcon
    };

    abstract val icon: ImageVector
}
