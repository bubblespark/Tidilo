package me.bubblespark.tidilo.service

import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import me.bubblespark.tidilo.TidiloApplication
import me.bubblespark.tidilo.config.AppLanguage
import me.bubblespark.tidilo.data.BookmarkRepository
import me.bubblespark.tidilo.data.Category
import me.bubblespark.tidilo.data.DomainIconManager
import me.bubblespark.tidilo.data.DomainPolicyManager
import me.bubblespark.tidilo.data.Subcategory
import me.bubblespark.tidilo.parser.ParsedWebPage
import me.bubblespark.tidilo.parser.WebPageParser
import me.bubblespark.tidilo.ui.screens.library.BookmarkItem
import me.bubblespark.tidilo.ui.screens.library.Tag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URI
import java.util.UUID

open class AiCoreExecutionException(
    val errorCode: Int,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

data class TaggingResult(
    val title: String,
    val summary: String,
    val category: String,
    val subcategory: String,
    val entities: List<String>,
    val rawAiOutput: String? = null
)

object GeminiTaggingService {

    /**
     * Parses the URL or uses pre-parsed snippets, generates summary and tags using Gemini Nano, and saves into Library.
     */
    suspend fun processAndSaveBookmark(
        url: String,
        language: AppLanguage,
        preferredTitle: String? = null,
        bodySnippet: String? = null,
        description: String? = null,
        siteName: String? = null,
        isNsfw: Boolean = false,
        ogImageUrl: String? = null
    ): Result<BookmarkItem> = withContext(Dispatchers.IO) {
        runCatching {
            val hasPreparsedData = !bodySnippet.isNullOrBlank()

            val realUrl: String
            val finalTitle: String
            val prompt: String
            val resolvedIsNsfw: Boolean
            var parsedWebPage: ParsedWebPage? = null
            val effectiveOgImageUrl: String?

            if (hasPreparsedData) {
                // Fast path: Bypass network fetch, DOM parsing, and icon downloads.
                // Precomputed metadata from enqueue time is used directly for instant prompt assembly.
                realUrl = url
                finalTitle = preferredTitle?.trim()?.takeIf { it.isNotBlank() && !isRawUrlOrHost(it, url) }
                    ?: extractHost(url)
                val effectiveSiteName = siteName?.trim()?.takeIf { it.isNotBlank() } ?: extractHost(url)
                val effectiveDescription = description.orEmpty()
                val effectiveSnippet = bodySnippet.orEmpty()
                resolvedIsNsfw = isNsfw
                effectiveOgImageUrl = ogImageUrl?.trim()?.takeIf { it.isNotBlank() }

                prompt = buildPromptWithSnippets(
                    url = realUrl,
                    siteName = effectiveSiteName,
                    title = finalTitle,
                    description = effectiveDescription,
                    bodySnippet = effectiveSnippet,
                    language = language
                )
            } else {
                // Fallback path: Full web fetch and DOM extraction (for legacy items or direct calls)
                val parseResult = WebPageParser.parseUrl(url)
                val parsed = parseResult.getOrElse { e ->
                    throw Exception("Network / Fetch Failed: ${e.message ?: "Could not connect to URL"}", e)
                }

                // Treat non-200 HTTP response codes as failure
                if (parsed.statusCode != 200) {
                    throw Exception("HTTP Error ${parsed.statusCode}: ${parsed.statusMessage.ifBlank { "Invalid status" }}")
                }

                realUrl = parsed.resolvedUrl.takeIf { it.isNotBlank() } ?: url
                finalTitle = parsed.ogpTags["og:title"]?.trim()?.takeIf { it.isNotBlank() }
                    ?: parsed.title?.trim()?.takeIf { it.isNotBlank() }
                    ?: preferredTitle?.trim()?.takeIf { it.isNotBlank() && !isRawUrlOrHost(it, realUrl) }
                    ?: extractHost(realUrl)

                prompt = buildPrompt(url = realUrl, parsed = parsed, language = language)
                parsedWebPage = parsed

                // Domain registration and NSFW policy check
                val domain = DomainIconManager.extractDomain(realUrl)
                val detectedSiteName = parsed.ogpTags["og:site_name"]?.trim()?.takeIf { it.isNotBlank() } ?: ""
                DomainPolicyManager.registerDomain(domain, false, detectedSiteName)
                resolvedIsNsfw = parsed.isNsfwSignal || DomainPolicyManager.isNsfwDomain(domain)

                // Verify domain icon cache and fetch only if not yet cached during execution
                if (domain != null && !DomainIconManager.hasIcon(TidiloApplication.instance, domain)) {
                    DomainIconManager.fetchAndSaveDomainIcon(
                        context = TidiloApplication.instance,
                        url = realUrl,
                        appleTouchIconUrl = parsed.appleTouchIconUrl,
                        faviconUrl = parsed.faviconUrl
                    )
                }
                effectiveOgImageUrl = ogImageUrl?.trim()?.takeIf { it.isNotBlank() }
                    ?: parsed.ogpTags["og:image"]?.trim()?.takeIf { it.isNotBlank() }
                    ?: parsed.twitterTags["twitter:image"]?.trim()?.takeIf { it.isNotBlank() }
            }

            // Run inference with Gemini Nano
            val aiOutput = try {
                runNanoInference(prompt)
            } catch (e: Exception) {
                throw mapInferenceException(e)
            }

            // Parse JSON and normalize results
            val result = parseAiResponse(aiOutput, finalTitle, parsedWebPage)

            // Construct BookmarkItem with resolved URL
            val bookmarkItem = BookmarkItem(
                id = UUID.randomUUID().toString(),
                title = result.title,
                url = realUrl,
                description = result.summary,
                tag = Tag.create(
                    category = result.category,
                    subcategory = result.subcategory,
                    entities = result.entities
                ),
                isNsfw = resolvedIsNsfw,
                ogImageUrl = effectiveOgImageUrl
            )

            // Save to Library (BookmarkRepository)
            BookmarkRepository.getInstance().addBookmark(bookmarkItem)

            bookmarkItem
        }
    }

    /**
     * Parses the URL and generates summary and tag proposal using Gemini Nano without adding to Library.
     */
    suspend fun generateProposal(
        url: String,
        language: AppLanguage,
        preferredTitle: String? = null
    ): Result<TaggingResult> = withContext(Dispatchers.IO) {
        runCatching {
            // 1. Parse web page and extract main body content
            val parseResult = WebPageParser.parseUrl(url)
            val parsed = parseResult.getOrElse { e ->
                throw Exception("Network / Fetch Failed: ${e.message ?: "Could not connect to URL"}", e)
            }

            // Treat non-200 HTTP response codes as failure
            if (parsed.statusCode != 200) {
                throw Exception("HTTP Error ${parsed.statusCode}: ${parsed.statusMessage.ifBlank { "Invalid status" }}")
            }

            // 2. Determine resolved URL and title (prefer og:title then <title>)
            val realUrl = parsed.resolvedUrl.takeIf { it.isNotBlank() } ?: url

            val finalTitle = parsed.ogpTags["og:title"]?.trim()?.takeIf { it.isNotBlank() }
                ?: parsed.title?.trim()?.takeIf { it.isNotBlank() }
                ?: preferredTitle?.trim()?.takeIf { it.isNotBlank() && !isRawUrlOrHost(it, realUrl) }
                ?: extractHost(realUrl)

            // 3. Build prompt with resolved URL
            val prompt = buildPrompt(url = realUrl, parsed = parsed, language = language)

            // 4. Run inference with Gemini Nano
            val aiOutput = try {
                runNanoInference(prompt)
            } catch (e: Exception) {
                throw mapInferenceException(e)
            }

            // 5. Parse JSON and normalize results
            val result = parseAiResponse(aiOutput, finalTitle, parsed)

            // Domain registration and icon check
            val domain = DomainIconManager.extractDomain(realUrl)
            val siteName = parsed.ogpTags["og:site_name"]?.trim()?.takeIf { it.isNotBlank() } ?: ""
            DomainPolicyManager.registerDomain(domain, false, siteName)

            if (domain != null && !DomainIconManager.hasIcon(TidiloApplication.instance, domain)) {
                DomainIconManager.fetchAndSaveDomainIcon(
                    context = TidiloApplication.instance,
                    url = realUrl,
                    appleTouchIconUrl = parsed.appleTouchIconUrl,
                    faviconUrl = parsed.faviconUrl
                )
            }

            result
        }
    }

    private fun isRawUrlOrHost(title: String, url: String): Boolean {
        return title.equals(url, ignoreCase = true) ||
                title.equals(extractHost(url), ignoreCase = true) ||
                title.startsWith("http://", ignoreCase = true) ||
                title.startsWith("https://", ignoreCase = true)
    }

    /**
     * Constructs the prompt payload sent to Gemini Nano from a parsed webpage.
     */
    fun buildPrompt(
        url: String,
        parsed: ParsedWebPage?,
        language: AppLanguage
    ): String {
        val siteName = parsed?.ogpTags?.get("og:site_name") ?: extractHost(url)
        val title = parsed?.ogpTags?.get("og:title") ?: parsed?.title ?: extractHost(url)
        val description = parsed?.ogpTags?.get("og:description")
            ?: parsed?.standardMetaTags?.get("description")
            ?: ""
        val bodySnippet = (parsed?.readabilityArticle?.textContent?.takeIf { it.isNotBlank() }
            ?: parsed?.jsoupBodyText
            ?: "").take(800).replace("\n", " ")

        return buildPromptWithSnippets(
            url = url,
            siteName = siteName,
            title = title,
            description = description,
            bodySnippet = bodySnippet,
            language = language
        )
    }

    /**
     * Constructs the prompt payload directly from raw snippet values without needing a ParsedWebPage instance.
     */
    fun buildPromptWithSnippets(
        url: String,
        siteName: String,
        title: String,
        description: String,
        bodySnippet: String,
        language: AppLanguage
    ): String {
        val summaryInstruction = if (language.isJapanese) {
            """
            - Language: Japanese.
            - Length: Exactly 1 concise sentence (approx. 50 characters).
            - Focus on what this web page introduces, explains, or provides, NOT the dictionary definition of the subject.
            - State objective facts; do NOT include promotional hype, sales talk, or subjective opinions.
            """.trimIndent()
        } else {
            """
            - Language: English.
            - Length: Exactly 1 concise sentence (under 100 characters, approx. 15-20 words).
            - Focus on what this web page introduces, explains, or provides, NOT the dictionary definition of the subject.
            - State objective facts; do NOT include promotional hype, sales talk, or subjective opinions.
            """.trimIndent()
        }

        return """
        You are a Web Content Tagging and Categorization Assistant.
        Analyze the provided web page information, write a 1-sentence summary, accurately classify categories, extract entity tags, and respond with ONLY a pure JSON string.

        [1. Summary Rules]
        $summaryInstruction

        [2. Category Guidelines]
        Select EXACTLY 1 most suitable category from the following 7 choices (use exact English key):
        - Article: Articles, blog posts, editorials, news, technical explainers, opinions.
        - Media: Illustrations, artwork, photos, videos, music (e.g., Pixiv, YouTube, portfolios).
        - Tool: Web applications, utilities, SaaS, code repositories, productivity tools (e.g., GitHub, Figma, online tools).
        - Commerce: E-commerce, online stores, product sales, pricing pages (e.g., Amazon, shopping sites).
        - Community: Social networks, discussion forums, Q&A, online communities (e.g., X/Twitter, Reddit, Discord).
        - Official: Company official portals, product landing pages, corporate overviews.
        - Reference: Official documentation, API references, dictionaries, wikis, specifications (e.g., MDN, Android Docs, Wikipedia).

        [3. Subcategory Rules]
        Select EXACTLY 1 most relevant subcategory from the following 11 choices (use exact English key):
        - Tech: Software, hardware, AI, programming, IT gadgets, engineering, internet infrastructure.
        - Business: Startups, finance, marketing, corporate management, careers, economy, B2B services.
        - Society: News, politics, law, environmental issues, social phenomena, culture, public affairs.
        - Life: Daily living, housing, health, wellness, relationships, parenting, personal finance, self-care.
        - Food: Recipes, cooking, restaurants, gourmet reviews, beverages, food culture, dining guides.
        - Travel: Tourism, local guides, hotels, transport, outdoor adventures, destination tips, sightseeing.
        - Game: Video games, board games, esports, gameplay guides, gaming news, game development.
        - Entertainment: Movies, TV shows, anime, manga, pop music, celebrity news, theater, humor.
        - Hobby: Crafts, photography, DIY, fitness, collecting, pet care, cars, personal creative pursuits.
        - Adult: NSFW content, pornography, mature dating services, erotic illustrations, age-restricted themes (18+).
        - Education: Academic subjects, language learning, tutorials, study resources, qualifications, courses.

        [4. Entities Tag Rules]
        - Extract between 2 to 10 specific noun keywords (e.g., tool/product names, company/brand names, technologies, creative work titles).
        - STRICTLY PROHIBIT the following noise expressions:
          * Dates, years, and temporal terms (e.g., "2026", "2025", "latest", "October", "spring", "today").
          * Subjective or exaggerated words (e.g., "best", "greatest", "ultimate", "recommended", "useful").
          * Ambiguous modifiers, annotations, or generic formats (e.g., "guide (latest)", "summary", "for beginners", "how-to", "tutorial").
          * Generic abstract nouns (e.g., "settings", "features", "methods", "list").
        - Keep the original or canonical notation as used in the content (e.g., keep "Android" as "Android", do NOT transliterate into Katakana "アンドロイド", and keep Japanese terms like "イラスト" in Japanese). Do NOT force translation.

        [Output Format]
        Respond with ONLY a valid JSON object. Do not include any explanations, markdown code blocks (such as ```json), or backticks.
        {
          "summary": "1-sentence summary",
          "category": "One of the 7 Category keys",
          "subcategory": "One of the 11 Subcategory keys",
          "entities": ["entity1", "entity2", "entity3"]
        }

        [Input Web Page Info]
        URL: $url
        Site: $siteName
        Title: $title
        Description: $description
        Content: $bodySnippet
        """.trimIndent()
    }

    /**
     * Executes AICore Gemini Nano inference.
     */
    private suspend fun runNanoInference(prompt: String): String {
        var model: GenerativeModel? = null
        try {
            model = Generation.getClient()
            val request = generateContentRequest(TextPart(prompt)) {
                // Set max output tokens to 256, sufficient for concise summary and JSON
                maxOutputTokens = 256
            }
            val response = model.generateContent(request)
            return response.candidates.firstOrNull()?.text?.trim().orEmpty()
        } finally {
            model?.close()
        }
    }

    /**
     * Parses JSON from AI response text and safely extracts TaggingResult.
     */
    fun parseAiResponse(
        rawOutput: String,
        fallbackTitle: String,
        parsedWebPage: ParsedWebPage?
    ): TaggingResult {
        val cleanJson = extractJsonSubstring(rawOutput)

        if (cleanJson.isNotBlank()) {
            try {
                val json = JSONObject(cleanJson)
                val summary = json.optString("summary").trim().ifBlank {
                    parsedWebPage?.ogpTags?.get("og:description")
                        ?: parsedWebPage?.standardMetaTags?.get("description")
                        ?: fallbackTitle
                }

                val rawCategory = json.optString("category").trim()
                val validCategory = validateCategory(rawCategory)

                val rawSubcategory = json.optString("subcategory").trim()
                val validSubcategory = validateSubcategory(rawSubcategory)

                val entitiesList = mutableListOf<String>()
                json.optJSONArray("entities")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val entity = arr.optString(i).trim().removePrefix("#").trim()
                        if (entity.isNotBlank() && isCleanEntity(entity)) {
                            entitiesList.add(entity)
                        }
                    }
                }

                // Adjust entities list to a bounded range (maximum 10)
                val safeEntities = entitiesList.distinct().take(10)

                return TaggingResult(
                    title = fallbackTitle,
                    summary = summary,
                    category = validCategory,
                    subcategory = validSubcategory,
                    entities = safeEntities,
                    rawAiOutput = rawOutput
                )
            } catch (_: Exception) {
                // Fallback on JSON parsing failure
            }
        }

        // Fallback
        val fallbackSummary = parsedWebPage?.ogpTags?.get("og:description")
            ?: parsedWebPage?.standardMetaTags?.get("description")
            ?: fallbackTitle

        return TaggingResult(
            title = fallbackTitle,
            summary = fallbackSummary,
            category = Category.entries.first().id,
            subcategory = Subcategory.entries.first().id,
            entities = listOfNotNull(parsedWebPage?.ogpTags?.get("og:site_name")?.takeIf { it.isNotBlank() }),
            rawAiOutput = rawOutput
        )
    }

    /**
     * Extracts the JSON substring between the first '{' and the last '}'.
     */
    private fun extractJsonSubstring(text: String): String {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        return if (start != -1 && end != -1 && end > start) {
            text.substring(start, end + 1)
        } else {
            ""
        }
    }

    private fun validateCategory(input: String): String {
        return Category.entries.firstOrNull { it.id.equals(input, ignoreCase = true) }?.id
            ?: Category.entries.first().id
    }

    private fun validateSubcategory(input: String): String {
        return Subcategory.entries.firstOrNull { it.id.equals(input, ignoreCase = true) }?.id
            ?: Subcategory.entries.first().id
    }

    private fun isCleanEntity(entity: String): Boolean {
        // Lightweight guard against generic noise words (years, buzzwords, list headers, etc.)
        val lower = entity.lowercase()
        val prohibited = listOf("2024", "2025", "2026", "2027", "latest", "最新", "最強", "まとめ", "入門", "おすすめ", "guide")
        return prohibited.none { lower.contains(it) }
    }

    private fun extractHost(url: String): String {
        return runCatching { URI(url).host ?: url }.getOrDefault(url)
    }

    /**
     * Maps low-level AI inference exceptions into AiCoreExecutionException preserving specific error codes.
     */
    fun mapInferenceException(e: Exception): AiCoreExecutionException {
        val errorCode = when (e) {
            is GenAiException -> e.errorCode
            is AiCoreExecutionException -> e.errorCode
            else -> when (val cause = e.cause) {
                is GenAiException -> cause.errorCode
                is AiCoreExecutionException -> cause.errorCode
                else -> null
            }
        }
        val msg = e.message.orEmpty()
        val friendlyMessage = when (errorCode) {
            GenAiException.ErrorCode.BUSY -> "AICore Rate Limit Exceeded: System service temporarily busy"
            GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED -> "AICore battery use quota exceeded (code 27)"
            GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED -> "AICore background use blocked (code 30)"
            else -> if (msg.contains("RESOURCE_EXHAUSTED", ignoreCase = true) || msg.contains("rate limit", ignoreCase = true)) {
                "AICore Rate Limit Exceeded: System service temporarily busy"
            } else if (msg.contains("UNAVAILABLE", ignoreCase = true)) {
                "AICore Service Unavailable on device"
            } else {
                "AICore Inference Failed: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        return AiCoreExecutionException(
            errorCode = errorCode ?: GenAiException.ErrorCode.UNKNOWN,
            message = friendlyMessage,
            cause = e
        )
    }
}
