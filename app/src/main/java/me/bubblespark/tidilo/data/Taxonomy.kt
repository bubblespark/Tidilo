package me.bubblespark.tidilo.data

import android.content.Context
import androidx.annotation.StringRes
import me.bubblespark.tidilo.R

/**
 * High-level category definition.
 * Uses English IDs (Article, Media, etc.) for AI prompts and internal storage,
 * and localizes based on language settings for UI presentation.
 */
enum class Category(
    val id: String,
    val enLabel: String,
    val jaLabel: String,
    @StringRes val labelRes: Int
) {
    ARTICLE("Article", "Article", "記事", R.string.category_article),
    MEDIA("Media", "Media", "動画・作品", R.string.category_media),
    TOOL("Tool", "Tool", "ツール", R.string.category_tool),
    COMMERCE("Commerce", "Commerce", "ショップ", R.string.category_commerce),
    COMMUNITY("Community", "Community", "コミュニティ", R.string.category_community),
    OFFICIAL("Official", "Official", "公式・紹介", R.string.category_official),
    REFERENCE("Reference", "Reference", "資料・情報", R.string.category_reference);

    fun getLabel(isJapanese: Boolean): String = if (isJapanese) jaLabel else enLabel
    fun getLabel(context: Context): String = context.getString(labelRes)

    companion object {
        fun fromId(id: String): Category? =
            entries.firstOrNull {
                it.id.equals(id, ignoreCase = true) ||
                        it.jaLabel.equals(id, ignoreCase = true) ||
                        it.enLabel.equals(id, ignoreCase = true)
            }

        fun toLocalized(id: String, isJapanese: Boolean): String =
            fromId(id)?.getLabel(isJapanese) ?: id

        fun toLocalized(id: String, context: Context): String =
            fromId(id)?.getLabel(context) ?: id
    }
}

/**
 * Mid-level subcategory definition.
 * Uses English IDs (Tech, Game, etc.) for AI prompts and internal storage,
 * and localizes based on language settings for UI presentation.
 */
enum class Subcategory(
    val id: String,
    val enLabel: String,
    val jaLabel: String,
    @StringRes val labelRes: Int
) {
    TECH("Tech", "Tech", "技術・IT", R.string.subcategory_tech),
    BUSINESS("Business", "Business", "ビジネス", R.string.subcategory_business),
    SOCIETY("Society", "Society", "ニュース・社会", R.string.subcategory_society),
    LIFE("Life", "Life", "暮らし・健康", R.string.subcategory_life),
    FOOD("Food", "Food", "グルメ・料理", R.string.subcategory_food),
    TRAVEL("Travel", "Travel", "旅行・交通", R.string.subcategory_travel),
    GAME("Game", "Game", "ゲーム", R.string.subcategory_game),
    ENTERTAINMENT("Entertainment", "Entertainment", "エンタメ", R.string.subcategory_entertainment),
    HOBBY("Hobby", "Hobby", "趣味・スポーツ", R.string.subcategory_hobby),
    ADULT("Adult", "Adult", "大人の嗜好", R.string.subcategory_adult),
    EDUCATION("Education", "Education", "教育", R.string.subcategory_education);

    fun getLabel(isJapanese: Boolean): String = if (isJapanese) jaLabel else enLabel
    fun getLabel(context: Context): String = context.getString(labelRes)

    companion object {
        fun fromId(id: String): Subcategory? =
            entries.firstOrNull {
                it.id.equals(id, ignoreCase = true) ||
                        it.jaLabel.equals(id, ignoreCase = true) ||
                        it.enLabel.equals(id, ignoreCase = true)
            }

        fun toLocalized(id: String, isJapanese: Boolean): String =
            fromId(id)?.getLabel(isJapanese) ?: id

        fun toLocalized(id: String, context: Context): String =
            fromId(id)?.getLabel(context) ?: id
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
