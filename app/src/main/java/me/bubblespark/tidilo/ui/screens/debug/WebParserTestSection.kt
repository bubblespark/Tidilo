package me.bubblespark.tidilo.ui.screens.debug

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.content.ClipData
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.bubblespark.tidilo.parser.JsonLdItem
import me.bubblespark.tidilo.parser.ParsedWebPage
import me.bubblespark.tidilo.parser.WebMetaTag
import me.bubblespark.tidilo.parser.WebPageParser
import me.bubblespark.tidilo.R
import kotlinx.coroutines.launch

private val WHITESPACE_REGEX = Regex("\\s+")

sealed class WebParserUiState {
    data object Idle : WebParserUiState()
    data class Loading(val url: String) : WebParserUiState()
    data class Success(val page: ParsedWebPage) : WebParserUiState()
    data class Error(val errorMessage: String, val cause: Throwable? = null) : WebParserUiState()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WebParserTestSection(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val focusManager = LocalFocusManager.current

    val copyToClipboard: (String, String) -> Unit = { text, toastMessage ->
        coroutineScope.launch {
            val clipData = ClipData.newPlainText("Text", text)
            clipboard.setClipEntry(clipData.toClipEntry())
        }
        Toast.makeText(context, toastMessage, Toast.LENGTH_SHORT).show()
    }

    var inputUrl by remember { mutableStateOf("https://developers.google.com/ml-kit/genai/prompt/android?hl=ja") }
    var parserState by remember { mutableStateOf<WebParserUiState>(WebParserUiState.Idle) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var metaSearchFilter by remember { mutableStateOf("") }
    var bodyExtractorMode by remember { mutableIntStateOf(0) } // 0: Readability4J, 1: Jsoup Clean, 2: Prompt

    val sampleUrls = remember {
        listOf(
            "ML Kit Prompt API" to "https://developers.google.com/ml-kit/genai/prompt/android?hl=ja",
            "Yahoo News" to "https://news.yahoo.co.jp/",
            "Qiita" to "https://qiita.com/",
            "Wikipedia" to "https://ja.wikipedia.org/wiki/Kotlin"
        )
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Language,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "URL Web Parser & Inspector",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Extract title, lang, meta, OGP, JSON-LD & body for AI tagging",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // URL Input field
            OutlinedTextField(
                value = inputUrl,
                onValueChange = { inputUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Web Page URL") },
                placeholder = { Text("https://example.com/article") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go
                ),
                keyboardActions = KeyboardActions(
                    onGo = {
                        focusManager.clearFocus()
                        if (inputUrl.isNotBlank()) {
                            coroutineScope.launch {
                                parserState = WebParserUiState.Loading(inputUrl)
                                val result = WebPageParser.parseUrl(inputUrl)
                                parserState = result.fold(
                                    onSuccess = { WebParserUiState.Success(it) },
                                    onFailure = { WebParserUiState.Error(it.message ?: "Fetch error", it) }
                                )
                            }
                        }
                    }
                ),
                leadingIcon = {
                    Icon(imageVector = Icons.Default.Link, contentDescription = null)
                },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (inputUrl.isNotEmpty()) {
                            IconButton(onClick = { inputUrl = "" }) {
                                Icon(imageVector = Icons.Default.Clear, contentDescription = "Clear")
                            }
                        }
                        IconButton(onClick = {
                            coroutineScope.launch {
                                val clipEntry = clipboard.getClipEntry()
                                val clip = clipEntry?.clipData?.getItemAt(0)?.text?.toString()
                                if (!clip.isNullOrBlank()) {
                                    inputUrl = clip
                                }
                            }
                        }) {
                            Icon(imageVector = Icons.Default.ContentPaste, contentDescription = "Paste")
                        }
                    }
                },
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                )
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Sample Preset Chips
            Text(
                text = "Preset Samples:",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                sampleUrls.forEach { (label, url) ->
                    AssistChip(
                        onClick = {
                            inputUrl = url
                            focusManager.clearFocus()
                        },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                        shape = RoundedCornerShape(10.dp),
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Parse Button
            Button(
                onClick = {
                    focusManager.clearFocus()
                    if (inputUrl.isNotBlank()) {
                        coroutineScope.launch {
                            parserState = WebParserUiState.Loading(inputUrl)
                            val result = WebPageParser.parseUrl(inputUrl)
                            parserState = result.fold(
                                onSuccess = { WebParserUiState.Success(it) },
                                onFailure = { WebParserUiState.Error(it.message ?: "Fetch error", it) }
                            )
                        }
                    }
                },
                enabled = inputUrl.isNotBlank() && parserState !is WebParserUiState.Loading,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                if (parserState is WebParserUiState.Loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Fetching & Parsing Web Elements...")
                } else {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Parse Web Elements")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Parser State View
            when (val state = parserState) {
                is WebParserUiState.Idle -> {
                    Text(
                        text = "Enter a URL above and tap 'Parse Web Elements' to view all extracted components.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                is WebParserUiState.Loading -> {
                    // Handled inside button / loading indicator
                }
                is WebParserUiState.Error -> {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = "Parse Failed:",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = state.errorMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
                is WebParserUiState.Success -> {
                    val page = state.page

                    // 1. Quick Stats Header
                    QuickStatsBar(page = page)

                    Spacer(modifier = Modifier.height(12.dp))

                    // 2. Tab Navigation
                    val tabTitles = listOf(
                        "Summary",
                        "Meta & OGP",
                        "JSON-LD (${page.jsonLdList.size})",
                        "All Tags (${page.metaTags.size})",
                        "Body Text"
                    )

                    SecondaryScrollableTabRow(
                        selectedTabIndex = selectedTab,
                        edgePadding = 0.dp,
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.4f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        tabTitles.forEachIndexed { index, title ->
                            Tab(
                                selected = selectedTab == index,
                                onClick = { selectedTab = index },
                                text = {
                                    Text(
                                        text = title,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1
                                    )
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // 3. Tab Contents
                    when (selectedTab) {
                        0 -> SummaryTab(page = page, onCopy = { copyToClipboard(it, "Copied to clipboard") })
                        1 -> MetaAndOgpTab(page = page, onCopy = { copyToClipboard(it, "Copied") })
                        2 -> JsonLdTab(page = page, onCopy = { copyToClipboard(it, "Copied JSON-LD") })
                        3 -> AllMetaTagsTab(
                            page = page,
                            searchQuery = metaSearchFilter,
                            onSearchChange = { metaSearchFilter = it },
                            onCopy = { copyToClipboard(it, "Copied") }
                        )
                        4 -> BodyTextTab(
                            page = page,
                            mode = bodyExtractorMode,
                            onModeChange = { bodyExtractorMode = it },
                            onCopy = { copyToClipboard(it, "Copied body text") }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickStatsBar(page: ParsedWebPage) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "HTTP ${page.statusCode} ${page.statusMessage}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (page.statusCode in 200..299) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                Text(
                    text = "Fetch: ${page.fetchLatencyMs}ms • Parse: ${page.parseLatencyMs}ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "Lang: ${page.htmlLang ?: "N/A"}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "HTML: ${(page.rawHtmlSizeBytes / 1024f).let { String.format("%.1f KB", it) }}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

@Composable
private fun SummaryTab(
    page: ParsedWebPage,
    onCopy: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        DetailItemCard(
            title = "Document Title (<title>)",
            value = page.title ?: "(None)",
            onCopy = { onCopy(page.title ?: "") }
        )

        DetailItemCard(
            title = "HTML Language (lang attribute)",
            value = page.htmlLang ?: "(None specified)",
            onCopy = { onCopy(page.htmlLang ?: "") }
        )

        DetailItemCard(
            title = "Canonical URL (<link rel='canonical'>)",
            value = page.canonicalUrl ?: "(None specified)",
            onCopy = { onCopy(page.canonicalUrl ?: "") }
        )

        DetailItemCard(
            title = "Final Redirected URL",
            value = page.finalUrl,
            onCopy = { onCopy(page.finalUrl) }
        )

        if (page.h1List.isNotEmpty()) {
            DetailItemCard(
                title = "H1 Headings (${page.h1List.size})",
                value = page.h1List.joinToString("\n• ", prefix = "• "),
                onCopy = { onCopy(page.h1List.joinToString("\n")) }
            )
        }

        if (page.h2List.isNotEmpty()) {
            DetailItemCard(
                title = "H2 Headings (${page.h2List.size})",
                value = page.h2List.take(6).joinToString("\n• ", prefix = "• ") +
                        if (page.h2List.size > 6) "\n... (+${page.h2List.size - 6} more)" else "",
                onCopy = { onCopy(page.h2List.joinToString("\n")) }
            )
        }
    }
}

@Composable
private fun MetaAndOgpTab(
    page: ParsedWebPage,
    onCopy: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // OGP Section
        Text(
            text = "Open Graph Protocol (OGP) - ${page.ogpTags.size} tags",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        if (page.ogpTags.isEmpty()) {
            Text(
                text = "No og:* meta tags found on this page.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        } else {
            page.ogpTags.forEach { (k, v) ->
                KeyValueRow(key = k, value = v, onCopy = { onCopy("$k: $v") })
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Twitter Cards Section
        Text(
            text = "Twitter Card Meta - ${page.twitterTags.size} tags",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.tertiary
        )

        if (page.twitterTags.isEmpty()) {
            Text(
                text = "No twitter:* meta tags found on this page.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        } else {
            page.twitterTags.forEach { (k, v) ->
                KeyValueRow(key = k, value = v, onCopy = { onCopy("$k: $v") })
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Standard Meta (description, keywords, author, etc.)
        Text(
            text = "Standard Meta - ${page.standardMetaTags.size} tags",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.secondary
        )

        if (page.standardMetaTags.isEmpty()) {
            Text(
                text = "No standard meta name tags found.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        } else {
            page.standardMetaTags.forEach { (k, v) ->
                KeyValueRow(key = k, value = v, onCopy = { onCopy("$k: $v") })
            }
        }
    }
}

@Composable
private fun JsonLdTab(
    page: ParsedWebPage,
    onCopy: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Detected JSON-LD (schema.org) Scripts: ${page.jsonLdList.size}",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )

        if (page.jsonLdList.isEmpty()) {
            Text(
                text = "No <script type=\"application/ld+json\"> elements detected on this page.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        } else {
            page.jsonLdList.forEachIndexed { index, item ->
                OutlinedCard(
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.outlinedCardColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                    )
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.padding(end = 8.dp)
                                ) {
                                    Text(
                                        text = "#${index + 1}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                                Text(
                                    text = "@type: ${item.type ?: "Unspecified"}",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            IconButton(
                                onClick = { onCopy(item.rawJson) },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "Copy JSON",
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        if (!item.headlineOrName.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Name/Headline: ${item.headlineOrName}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        if (!item.description.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Description: ${item.description}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Raw Formatted JSON Display
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = item.rawJson,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                ),
                                modifier = Modifier
                                    .padding(10.dp)
                                    .heightIn(max = 200.dp)
                                    .verticalScroll(rememberScrollState()),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AllMetaTagsTab(
    page: ParsedWebPage,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onCopy: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Search Filter TextField
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Filter Meta Tags (${page.metaTags.size} total)") },
            placeholder = { Text("e.g. og:, description, viewport...") },
            singleLine = true,
            leadingIcon = {
                Icon(imageVector = Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchChange("") }) {
                        Icon(imageVector = Icons.Default.Clear, contentDescription = "Clear", modifier = Modifier.size(16.dp))
                    }
                }
            },
            shape = RoundedCornerShape(12.dp)
        )

        val filteredList = remember(page.metaTags, searchQuery) {
            if (searchQuery.isBlank()) {
                page.metaTags
            } else {
                page.metaTags.filter {
                    it.keyName.contains(searchQuery, ignoreCase = true) ||
                            it.content.contains(searchQuery, ignoreCase = true) ||
                            it.keyType.contains(searchQuery, ignoreCase = true)
                }
            }
        }

        Text(
            text = "Showing ${filteredList.size} of ${page.metaTags.size} meta tags:",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )

        filteredList.forEach { tag ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = when (tag.keyType) {
                                    "property" -> MaterialTheme.colorScheme.primaryContainer
                                    "name" -> MaterialTheme.colorScheme.secondaryContainer
                                    "http-equiv" -> MaterialTheme.colorScheme.tertiaryContainer
                                    else -> MaterialTheme.colorScheme.surfaceVariant
                                }
                            ) {
                                Text(
                                    text = tag.keyType,
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = tag.keyName,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = tag.content.ifBlank { "(Empty value)" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    IconButton(
                        onClick = { onCopy("<meta ${tag.keyType}=\"${tag.keyName}\" content=\"${tag.content}\">") },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy tag",
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun BodyTextTab(
    page: ParsedWebPage,
    mode: Int,
    onModeChange: (Int) -> Unit,
    onCopy: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Wrap extraction and filtered-body choices on narrow screens and with large fonts.
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            FilterChip(
                selected = mode == 0,
                onClick = { onModeChange(0) },
                label = {
                    Text("Readability4J (${page.readabilityArticle?.charCount ?: 0} chars)")
                },
                shape = RoundedCornerShape(10.dp),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )

            FilterChip(
                selected = mode == 1,
                onClick = { onModeChange(1) },
                label = {
                    Text("Jsoup Clean (${page.jsoupBodyCharCount} chars)")
                },
                shape = RoundedCornerShape(10.dp),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            )

            FilterChip(
                selected = mode == 2,
                onClick = { onModeChange(2) },
                label = { Text(stringResource(R.string.debug_parser_prompt)) },
                shape = RoundedCornerShape(10.dp)
            )
        }

        val activeText = when (mode) {
            0 -> page.readabilityArticle?.textContent.orEmpty()
            1 -> page.jsoupBodyText
            else -> page.promptBodyText
        }

        val activeCharCount = activeText.length

        // Details header
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column {
                Text(
                    text = when (mode) {
                        0 -> "Mozilla Readability Algorithm"
                        1 -> "Jsoup Heuristic DOM Stripping"
                        else -> stringResource(R.string.debug_parser_prompt_description)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = run {
                        val wordCount = if (activeText.isBlank()) 0 else activeText.trim().split(WHITESPACE_REGEX).size
                        "Total extracted: $activeCharCount characters ($wordCount words)"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            Button(
                onClick = { onCopy(activeText) },
                modifier = Modifier.align(Alignment.End),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Copy Body", style = MaterialTheme.typography.labelSmall)
            }
        }

        if (mode == 0 && page.readabilityArticle?.excerpt?.isNotBlank() == true) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(
                        text = "Readability Excerpt:",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = page.readabilityArticle.excerpt,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
        }

        // Extracted Text Content Box
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (activeText.isBlank()) {
                Text(
                    text = "No body text extracted with this method.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(14.dp)
                )
            } else {
                Text(
                    text = activeText,
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .padding(14.dp)
                        .heightIn(max = 350.dp)
                        .verticalScroll(rememberScrollState())
                )
            }
        }
    }
}

@Composable
private fun DetailItemCard(
    title: String,
    value: String,
    onCopy: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            IconButton(onClick = onCopy, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "Copy",
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

@Composable
private fun KeyValueRow(
    key: String,
    value: String,
    onCopy: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = key,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = value.ifBlank { "(Empty)" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onCopy, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "Copy",
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}
