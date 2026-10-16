package me.bubblespark.tidilo.ui.screens.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.bubblespark.tidilo.config.AppConfig
import me.bubblespark.tidilo.data.BookmarkRepository
import me.bubblespark.tidilo.data.DomainIconManager
import me.bubblespark.tidilo.data.DomainPolicyManager

@Composable
fun NsfwSettingsScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val currentLanguage by AppConfig.currentLanguage.collectAsStateWithLifecycle()
    val isNsfwVisible by AppConfig.isNsfwContentVisible.collectAsStateWithLifecycle()
    val isNsfwBookmarkable by AppConfig.isNsfwContentBookmarkable.collectAsStateWithLifecycle()
    val policies by DomainPolicyManager.policies.collectAsStateWithLifecycle()
    val iconVersion by DomainIconManager.iconUpdateVersion.collectAsStateWithLifecycle()

    // Ensure all domains from current bookmarks are synced
    val bookmarkRepo = remember { BookmarkRepository.getInstance(context) }
    val bookmarks by bookmarkRepo.bookmarks.collectAsStateWithLifecycle()
    remember(bookmarks) {
        val domains = bookmarks.mapNotNull { DomainIconManager.extractDomain(it.url) }
        DomainPolicyManager.syncDomains(domains)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Header with Back button
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onNavigateBack,
                modifier = Modifier.size(44.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (currentLanguage.isJapanese) "NSFW Contents" else "NSFW Contents",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 1. Option: Show NSFW content (Default ON)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
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
                            imageVector = Icons.Default.Visibility,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = if (currentLanguage.isJapanese) "NSFWコンテンツを表示する。" else "Show NSFW Content",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (currentLanguage.isJapanese)
                            "OFFにするとライブラリや検索で\nNSFWブックマークを非表示にします"
                        else
                            "Show or hide NSFW bookmarks in library and search",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Switch(
                    checked = isNsfwVisible,
                    onCheckedChange = { AppConfig.setNsfwContentVisible(it) }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 2. Option: Bookmark NSFW content (Default ON)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
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
                            imageVector = Icons.Default.Bookmark,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = if (currentLanguage.isJapanese) "NSFWコンテンツをブックマークする。" else "Bookmark NSFW Content",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (currentLanguage.isJapanese)
                            "NSFWコンテンツのキュー追加と保存を許可\n（今後実装予定）"
                        else
                            "Allow queuing and bookmarking NSFW pages (Upcoming feature)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Switch(
                    checked = isNsfwBookmarkable,
                    onCheckedChange = { AppConfig.setNsfwContentBookmarkable(it) }
                )
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        // 3. Section: NSFW Domain Management
        Text(
            text = if (currentLanguage.isJapanese) "NSFW ドメイン管理" else "NSFW Domain Management",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )

        Text(
            text = if (currentLanguage.isJapanese)
                "チェックが有効なドメインはNSFWドメインとして扱われます。"
            else
                "Checked domains are designated and treated as NSFW sources.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(start = 4.dp, bottom = 12.dp)
        )

        if (policies.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
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
                        text = if (currentLanguage.isJapanese) "保存されているドメインはありません" else "No domains saved yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(vertical = 8.dp)
                ) {
                    policies.forEachIndexed { index, policy ->
                        val isChecked = policy.isNsfw
                        val iconBitmap = remember(policy.domain, iconVersion) {
                            DomainIconManager.getIconBitmap(context, policy.domain)
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    DomainPolicyManager.setDomainNsfw(policy.domain, !isChecked)
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = { checked ->
                                    DomainPolicyManager.setDomainNsfw(policy.domain, checked)
                                }
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
