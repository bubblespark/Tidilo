package me.bubblespark.tidilo.ui.screens.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.data.Category
import me.bubblespark.tidilo.data.Subcategory
import me.bubblespark.tidilo.ui.components.DomainWatermark
import me.bubblespark.tidilo.ui.components.TidiloPageHeader
import me.bubblespark.tidilo.ui.components.TidiloScreenScaffold
import me.bubblespark.tidilo.ui.components.TidiloSurface
import me.bubblespark.tidilo.ui.theme.SurfaceRole
import me.bubblespark.tidilo.ui.theme.SurfaceVariant
import me.bubblespark.tidilo.ui.theme.ThemeCorner
import me.bubblespark.tidilo.ui.theme.themeCorner
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val trashDetailDate = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")

/** Read-only content has no editor observers, engagement callbacks or OG image loader. */
@Composable
internal fun TrashBookmarkDetailContent(bookmark: BookmarkItem, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    LocalConfiguration.current
    val zone = ZoneId.systemDefault()
    val scroll = rememberScrollState()
    val openUrl = { openBookmarkInBrowser(context, bookmark.url) {} }
    TidiloScreenScaffold(
        header = { TidiloPageHeader(stringResource(R.string.detail_title), onBack, scrollState = scroll, raisedTitle = true) },
        modifier = modifier,
        bottomContentPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 108.dp,
        overlay = {
            TidiloSurface(onClick = openUrl, role = SurfaceRole.PRIMARY_ACTION, variant = SurfaceVariant.COMPACT,
                modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 24.dp, bottom = 31.dp).size(54.dp)) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(ImageVector.vectorResource(R.drawable.ic_open_in_browser), stringResource(R.string.bookmark_open_in_browser), Modifier.size(22.dp))
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(padding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.fillMaxWidth()) {
                DomainWatermark(bookmark.domain, Modifier.align(Alignment.TopEnd))
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Spacer(Modifier.height(8.dp))
                    Text(bookmark.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Surface(onClick = openUrl, shape = themeCorner(ThemeCorner.FIELD),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)) {
                        Text(bookmark.url, Modifier.fillMaxWidth().padding(14.dp), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            TrashDetailSectionLabel(stringResource(R.string.detail_section_tags))
            BookmarkTagSection(bookmark.tag,
                bookmark.tag.category?.let { Category.toLocalized(it, context) }.orEmpty(),
                bookmark.tag.subcategory?.let { Subcategory.toLocalized(it, context) }.orEmpty(), bookmark.isNsfw)
            Spacer(Modifier.height(4.dp))
            TrashDetailSectionLabel(stringResource(R.string.detail_section_summary))
            Card(shape = themeCorner(ThemeCorner.FIELD), colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))) {
                Text(bookmark.description, Modifier.fillMaxWidth().padding(14.dp), style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(4.dp))
            TrashDetailSectionLabel(stringResource(R.string.detail_section_note))
            OutlinedTextField(value = bookmark.note, onValueChange = {}, readOnly = true,
                modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 150.dp).testTag("trash_note"),
                shape = themeCorner(ThemeCorner.FIELD), colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)))
            Spacer(Modifier.height(12.dp))
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                Text(stringResource(R.string.detail_created_at, trashDetailDate.format(Instant.ofEpochMilli(bookmark.createdAt).atZone(zone))),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.detail_updated_at, trashDetailDate.format(Instant.ofEpochMilli(bookmark.updatedAt).atZone(zone))),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun TrashDetailSectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
}
