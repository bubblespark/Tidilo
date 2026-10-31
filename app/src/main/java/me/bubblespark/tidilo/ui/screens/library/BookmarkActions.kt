package me.bubblespark.tidilo.ui.screens.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import me.bubblespark.tidilo.R
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures

@Composable
internal fun BookmarkLikeIndicator(liked: Boolean) {
    Icon(
        imageVector = if (liked) Icons.Filled.Favorite else ImageVector.vectorResource(R.drawable.ic_favorite),
        contentDescription = stringResource(if (liked) R.string.bookmark_liked else R.string.bookmark_not_liked),
        tint = if (liked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(20.dp).pointerInput(Unit) { detectTapGestures(onTap = {}) }
    )
}

internal fun openBookmarkInBrowser(context: Context, url: String, onOpened: () -> Unit) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        })
    }.onSuccess { onOpened() }.onFailure {
        Toast.makeText(context, context.getString(R.string.bookmark_could_not_open_browser), Toast.LENGTH_SHORT).show()
    }
}
