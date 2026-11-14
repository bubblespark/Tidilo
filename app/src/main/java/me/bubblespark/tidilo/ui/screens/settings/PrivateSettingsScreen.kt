package me.bubblespark.tidilo.ui.screens.settings

import me.bubblespark.tidilo.ui.theme.ThemeCorner
import me.bubblespark.tidilo.ui.theme.themeCorner
import me.bubblespark.tidilo.ui.components.TidiloPageHeader
import me.bubblespark.tidilo.ui.components.screenSystemInsets
import me.bubblespark.tidilo.ui.components.TidiloScreenScaffold

import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.bubblespark.tidilo.R
import me.bubblespark.tidilo.config.AppConfig
import me.bubblespark.tidilo.data.BookmarkRepository
import android.content.Context
import me.bubblespark.tidilo.data.DomainPolicy
import me.bubblespark.tidilo.data.DomainPolicyStore
import me.bubblespark.tidilo.ui.components.rememberDomainVisual

@Composable
fun PrivateSettingsScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val domainPolicyStore = remember(context) { DomainPolicyStore.getInstance(context) }
    val isPrivateVisible by AppConfig.isPrivateContentVisible.collectAsStateWithLifecycle()
    val isPrivateBookmarkable by AppConfig.isPrivateContentBookmarkable.collectAsStateWithLifecycle()
    val policies by domainPolicyStore.policies.collectAsStateWithLifecycle()

    // Ensure all domains from current bookmarks are synced
    val bookmarkRepo = remember(context) { BookmarkRepository.getInstance(context) }
    val persistenceScope = androidx.compose.runtime.rememberCoroutineScope()
    val initializationFailure by bookmarkRepo.initializationFailure.collectAsStateWithLifecycle()
    val domains by remember(bookmarkRepo) { bookmarkRepo.observeDomains() }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    androidx.compose.runtime.LaunchedEffect(domains, domainPolicyStore) {
        domainPolicyStore.syncDomains(domains)
    }

    TidiloScreenScaffold(contentWindowInsets = screenSystemInsets(),
        modifier = modifier,
        hideHeaderOnScroll = false,
        // Header with Back button
        header = {
            TidiloPageHeader(
                title = stringResource(R.string.title_private_content),
                onNavigateBack = onNavigateBack
            )
        }
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(contentPadding)
        ) {
            initializationFailure?.let { me.bubblespark.tidilo.ui.screens.library.PagingFeedback(androidx.paging.LoadState.Error(it), bookmarkRepo::retryInitialization) }
            // 1. Option: Show Private Content (Default ON)
            PrivateOptionCard(
                icon = Icons.Default.Visibility,
                title = stringResource(R.string.private_show_content_title),
                subtitle = stringResource(R.string.private_show_content_subtitle),
                checked = isPrivateVisible,
                onCheckedChange = { AppConfig.setPrivateContentVisible(it) }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 2. Option: Bookmark Private Content (Default ON)
            PrivateOptionCard(
                icon = Icons.Default.Bookmark,
                title = stringResource(R.string.private_bookmark_content_title),
                subtitle = stringResource(R.string.private_bookmark_content_subtitle),
                checked = isPrivateBookmarkable,
                onCheckedChange = { AppConfig.setPrivateContentBookmarkable(it) }
            )

            Spacer(modifier = Modifier.height(28.dp))

            // 3. Section: Private Domain Management
            Text(
                text = stringResource(R.string.private_domain_management_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
            )

            Text(
                text = stringResource(R.string.private_domain_management_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(start = 4.dp, bottom = 12.dp)
            )

            if (policies.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = themeCorner(ThemeCorner.IMAGE),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.private_no_domains),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = themeCorner(ThemeCorner.CARD),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 8.dp)
                    ) {
                        policies.forEachIndexed { index, policy ->
                            PrivateDomainPolicyItem(
                                policy = policy,
                                context = context,
                                onCheckedChange = { checked ->
                                    persistenceScope.launch {
                                        try {
                                            domainPolicyStore.setDomainPrivateAndAwait(policy.domain, checked)
                                        } catch (e: Exception) {
                                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                            android.widget.Toast.makeText(
                                                context,
                                                context.getString(R.string.common_save_failed),
                                                android.widget.Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    }
                                }
                            )

                            if (index < policies.size - 1) {
                                Spacer(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(1.dp)
                                        .padding(horizontal = 16.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun PrivateOptionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = themeCorner(ThemeCorner.CARD),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = checked,
                    role = Role.Switch,
                    onValueChange = onCheckedChange
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Switch(
                checked = checked,
                onCheckedChange = null
            )
        }
    }
}

@Composable
private fun PrivateDomainPolicyItem(
    policy: DomainPolicy,
    context: Context,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val isChecked = policy.isPrivate
    val domainVisual by rememberDomainVisual(policy.domain, allowNetwork = false, context = context)
    val iconBitmap = domainVisual.bitmap

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable {
                onCheckedChange(!isChecked)
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = isChecked,
            onCheckedChange = onCheckedChange
        )

        Spacer(modifier = Modifier.width(8.dp))

        if (iconBitmap != null) {
            Image(
                bitmap = iconBitmap,
                contentDescription = null,
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
            )
            Spacer(modifier = Modifier.width(12.dp))
        } else {
            Icon(
                imageVector = Icons.Default.Language,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
        }

        Text(
            text = policy.domain,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (isChecked) FontWeight.SemiBold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}
