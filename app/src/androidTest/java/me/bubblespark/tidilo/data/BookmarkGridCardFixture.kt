package me.bubblespark.tidilo.data

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.bubblespark.tidilo.ui.screens.library.BookmarkGridCard
import me.bubblespark.tidilo.ui.screens.library.BookmarkItem
import me.bubblespark.tidilo.ui.screens.library.bookmarkGridLayout

@Composable
internal fun BookmarkGridCardFixture(
    bookmark: BookmarkItem,
    modifier: Modifier = Modifier,
    widthModifier: Modifier = Modifier,
    mediaHeight: Dp? = null,
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    onToggleLike: () -> Unit = {}
) {
    BoxWithConstraints(widthModifier) {
        val layout = bookmarkGridLayout(PaddingValues(0.dp), trailingPadding = 0.dp)
        BookmarkGridCard(
            bookmark = bookmark,
            modifier = modifier,
            onClick = onClick,
            onLongClick = onLongClick,
            onToggleLike = onToggleLike,
            layout = if (mediaHeight == null) layout else layout.copy(mediaHeight = mediaHeight)
        )
    }
}
