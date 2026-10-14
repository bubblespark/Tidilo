package me.bubblespark.tidilo.data

/**
 * High-level category definition.
 * Uses English IDs (Article, Media, etc.) for AI prompts and internal storage,
 * and localizes based on language settings for UI presentation.
 */
enum class Category(val id: String, val enLabel: String, val jaLabel: String) {
    ARTICLE("Article", "Article", "記事"),
    MEDIA("Media", "Media", "動画・作品"),
    TOOL("Tool", "Tool", "ツール"),
    COMMERCE("Commerce", "Commerce", "ショップ"),
    COMMUNITY("Community", "Community", "コミュニティ"),
    OFFICIAL("Official", "Official", "公式・紹介"),
    REFERENCE("Reference", "Reference", "資料・情報");

    fun getLabel(isJapanese: Boolean): String = if (isJapanese) jaLabel else enLabel

    companion object {
        fun fromId(id: String): Category? =
            entries.firstOrNull {
                it.id.equals(id, ignoreCase = true) ||
                        it.jaLabel.equals(id, ignoreCase = true) ||
                        it.enLabel.equals(id, ignoreCase = true)
            }

        fun toLocalized(id: String, isJapanese: Boolean): String =
            fromId(id)?.getLabel(isJapanese) ?: id
    }
}

/**
 * Mid-level subcategory definition.
 * Uses English IDs (Tech, Game, etc.) for AI prompts and internal storage,
 * and localizes based on language settings for UI presentation.
 */
enum class Subcategory(val id: String, val enLabel: String, val jaLabel: String) {
    TECH("Tech", "Tech", "技術・IT"),
    BUSINESS("Business", "Business", "ビジネス"),
    SOCIETY("Society", "Society", "ニュース・社会"),
    LIFE("Life", "Life", "暮らし・健康"),
    FOOD("Food", "Food", "グルメ・料理"),
    TRAVEL("Travel", "Travel", "旅行・交通"),
    GAME("Game", "Game", "ゲーム"),
    ENTERTAINMENT("Entertainment", "Entertainment", "エンタメ"),
    HOBBY("Hobby", "Hobby", "趣味・スポーツ"),
    ADULT("Adult", "Adult", "大人の嗜好"),
    EDUCATION("Education", "Education", "教育");

    fun getLabel(isJapanese: Boolean): String = if (isJapanese) jaLabel else enLabel

    companion object {
        fun fromId(id: String): Subcategory? =
            entries.firstOrNull {
                it.id.equals(id, ignoreCase = true) ||
                        it.jaLabel.equals(id, ignoreCase = true) ||
                        it.enLabel.equals(id, ignoreCase = true)
            }

        fun toLocalized(id: String, isJapanese: Boolean): String =
            fromId(id)?.getLabel(isJapanese) ?: id
    }
}

/**
 * Taxonomy helper for AI prompt generation.
 * Injects a fixed list of English taxonomy IDs into system instructions.
 */
object TaxonomyPromptHelper {
    fun getCategoriesPromptString(): String =
        Category.entries.joinToString(", ") { it.id }

    fun getSubcategoriesPromptString(): String =
        Subcategory.entries.joinToString(", ") { it.id }

    fun getPromptGuidelines(): String = """
Classify the given web content into three attributes:
1. "category": Must be strictly one of [${getCategoriesPromptString()}]
2. "subcategory": Must be strictly one of [${getSubcategoriesPromptString()}]
3. "entities": Extract 1 to 3 distinctive proper nouns, brands, product names, or core keywords (keep original language).

Respond in JSON format:
{
  "category": "...",
  "subcategory": "...",
  "entities": ["...", "..."]
}
""".trimIndent()
}
