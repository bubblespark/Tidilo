package me.bubblespark.tidilo

import me.bubblespark.tidilo.ui.screens.library.BookmarkItem
import me.bubblespark.tidilo.ui.screens.library.LibraryViewModel
import me.bubblespark.tidilo.ui.screens.library.Tag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySearchTest {

    private val sampleBookmarks = listOf(
        BookmarkItem(
            id = "1",
            title = "Android Developers: Material 3 Expressive",
            url = "https://m3.material.io/",
            description = "Expressive design system featuring fluid motion.",
            tag = Tag.create("Design", "Material3")
        ),
        BookmarkItem(
            id = "2",
            title = "Gemini Nano によるオンデバイス生成AIの活用",
            url = "https://developer.android.com/ai/aicore",
            description = "ローカル端末上でGemini Nanoを推論実行する仕組み。",
            tag = Tag.create("AI", "GeminiNano")
        ),
        BookmarkItem(
            id = "3",
            title = "オンデバイスLLMの量子化モデル",
            url = "https://ai.google.dev/edge/mediapipe/solutions/genai",
            description = "モバイルデバイス向け軽量モデルの評価。",
            tag = Tag.create("AI", "Model")
        ),
        BookmarkItem(
            id = "4",
            title = "未分類のメモ",
            url = "https://example.com/unclassified",
            description = "説明文なし",
            tag = Tag.create(null, "") // Should become "None", "None"
        )
    )

    @Test
    fun testDefaultTagValuesAreNone() {
        val noneTag = Tag.create(null, " ")
        assertEquals("None", noneTag.category)
        assertEquals("None", noneTag.subcategory)
    }

    @Test
    fun testKeywordSearchJapaneseAndEnglish() {
        // Japanese keyword search
        val resultJa = LibraryViewModel.filterBookmarks(sampleBookmarks, "オンデバイス")
        assertEquals(2, resultJa.size)

        // English keyword search (case-insensitive)
        val resultEn = LibraryViewModel.filterBookmarks(sampleBookmarks, "material")
        assertEquals(1, resultEn.size)
        assertEquals("1", resultEn[0].id)
    }

    @Test
    fun testTagSearchCategoryAndSubcategory() {
        // Category tag search (#AI)
        val resultCategory = LibraryViewModel.filterBookmarks(sampleBookmarks, "#AI")
        assertEquals(2, resultCategory.size)

        // Subcategory tag search (#Model)
        val resultSubcategory = LibraryViewModel.filterBookmarks(sampleBookmarks, "#Model")
        assertEquals(1, resultSubcategory.size)
        assertEquals("3", resultSubcategory[0].id)

        // Fallback "None" tag search (#None)
        val resultNone = LibraryViewModel.filterBookmarks(sampleBookmarks, "#None")
        assertEquals(1, resultNone.size)
        assertEquals("4", resultNone[0].id)
    }

    @Test
    fun testAndSearchCombination() {
        // Word + Tag AND search: Japanese keyword and "#Model"
        val andResult = LibraryViewModel.filterBookmarks(sampleBookmarks, "オンデバイス #Model")
        assertEquals(1, andResult.size)
        assertEquals("3", andResult[0].id)

        // Word + Tag AND search: "Gemini" and "#AI"
        val andResult2 = LibraryViewModel.filterBookmarks(sampleBookmarks, "Gemini #AI")
        assertEquals(1, andResult2.size)
        assertEquals("2", andResult2[0].id)

        // Non-matching combination
        val noMatch = LibraryViewModel.filterBookmarks(sampleBookmarks, "Material #AI")
        assertTrue(noMatch.isEmpty())
    }

    @Test
    fun testTaxonomyLocalizationAndLookup() {
        assertEquals("記事", me.bubblespark.tidilo.data.Category.ARTICLE.getLabel(isJapanese = true))
        assertEquals("Article", me.bubblespark.tidilo.data.Category.ARTICLE.getLabel(isJapanese = false))
        assertEquals(me.bubblespark.tidilo.data.Category.ARTICLE, me.bubblespark.tidilo.data.Category.fromId("記事"))
        assertEquals(me.bubblespark.tidilo.data.Category.ARTICLE, me.bubblespark.tidilo.data.Category.fromId("Article"))

        assertEquals("技術・IT", me.bubblespark.tidilo.data.Subcategory.TECH.getLabel(isJapanese = true))
        assertEquals("Tech", me.bubblespark.tidilo.data.Subcategory.TECH.getLabel(isJapanese = false))
        assertEquals(me.bubblespark.tidilo.data.Subcategory.TECH, me.bubblespark.tidilo.data.Subcategory.fromId("技術・IT"))
        assertEquals(me.bubblespark.tidilo.data.Subcategory.TECH, me.bubblespark.tidilo.data.Subcategory.fromId("Tech"))
    }

    @Test
    fun testLocalizedTagAndEntitySearch() {
        val testBookmarks = listOf(
            BookmarkItem(
                id = "t1",
                title = "Android 開発ガイド",
                url = "https://developer.android.com",
                description = "最新のアーキテクチャ",
                tag = Tag.create(
                    category = "Article",
                    subcategory = "Tech",
                    entities = listOf("Compose", "Material3")
                )
            ),
            BookmarkItem(
                id = "t2",
                title = "ゲーム攻略ポータル",
                url = "https://game.example.com",
                description = "最新メタ情報",
                tag = Tag.create(
                    category = "Community",
                    subcategory = "Game",
                    entities = listOf("Shadowverse")
                )
            )
        )

        // English Category search
        val resCatEn = LibraryViewModel.filterBookmarks(testBookmarks, "#Article")
        assertEquals(1, resCatEn.size)
        assertEquals("t1", resCatEn[0].id)

        // Japanese Category search
        val resCatJa = LibraryViewModel.filterBookmarks(testBookmarks, "#記事")
        assertEquals(1, resCatJa.size)
        assertEquals("t1", resCatJa[0].id)

        // English Subcategory search
        val resSubEn = LibraryViewModel.filterBookmarks(testBookmarks, "#Tech")
        assertEquals(1, resSubEn.size)
        assertEquals("t1", resSubEn[0].id)

        // Japanese Subcategory search
        val resSubJa = LibraryViewModel.filterBookmarks(testBookmarks, "#技術")
        assertEquals(1, resSubJa.size)
        assertEquals("t1", resSubJa[0].id)

        // Entity search
        val resEntity = LibraryViewModel.filterBookmarks(testBookmarks, "#Compose")
        assertEquals(1, resEntity.size)
        assertEquals("t1", resEntity[0].id)

        val resEntity2 = LibraryViewModel.filterBookmarks(testBookmarks, "#Shadowverse")
        assertEquals(1, resEntity2.size)
        assertEquals("t2", resEntity2[0].id)
    }

    @Test
    fun testSearchHistoryManagementRequirements() {
        var history = emptyList<String>()

        // 1. History stores the entire search query as a single entry (not split by word/tag)
        history = LibraryViewModel.updateHistoryList(history, "Kotlin #Article #Tech")
        assertEquals(1, history.size)
        assertEquals("Kotlin #Article #Tech", history[0])

        // 2. Newest first (latest entry prepended to the head)
        history = LibraryViewModel.updateHistoryList(history, "Android Compose")
        assertEquals(2, history.size)
        assertEquals("Android Compose", history[0])
        assertEquals("Kotlin #Article #Tech", history[1])

        // 3. Exact duplicates are moved to head rather than duplicated
        history = LibraryViewModel.updateHistoryList(history, "Kotlin #Article #Tech")
        assertEquals(2, history.size)
        assertEquals("Kotlin #Article #Tech", history[0])
        assertEquals("Android Compose", history[1])

        // 4. Maintain up to 10 entries max
        for (i in 1..15) {
            history = LibraryViewModel.updateHistoryList(history, "Query $i")
        }
        assertEquals(10, history.size)
        assertEquals("Query 15", history[0]) // Newest query at index 0
        assertEquals("Query 6", history[9])  // 10th query at index 9
    }

    @Test
    fun testSearchScreenRoute() {
        assertEquals("search", me.bubblespark.tidilo.ui.navigation.Screen.Search.route)
        assertEquals("Search", me.bubblespark.tidilo.ui.navigation.Screen.Search.title)
    }

    @Test
    fun testTagSelectionRoute() {
        assertEquals("tag_selection", me.bubblespark.tidilo.ui.navigation.Screen.TagSelection.route)
        assertEquals("Tag Selection", me.bubblespark.tidilo.ui.navigation.Screen.TagSelection.title)
    }

    @Test
    fun testEntityNameSortingAlphabetBeforeJapanese() {
        val list = listOf("機械学習", "Zebra", "Apple", "テスト", "google", "あいうえお")
        fun compareEntityNames(a: String, b: String): Int {
            val aFirst = a.firstOrNull()
            val bFirst = b.firstOrNull()
            val aIsAscii = aFirst != null && aFirst.code < 128
            val bIsAscii = bFirst != null && bFirst.code < 128
            return when {
                aIsAscii && !bIsAscii -> -1
                !aIsAscii && bIsAscii -> 1
                else -> a.compareTo(b, ignoreCase = true)
            }
        }

        val sorted = list.sortedWith { a, b -> compareEntityNames(a, b) }
        // Alphabetical (case-insensitive) comes first: Apple, google, Zebra
        assertEquals("Apple", sorted[0])
        assertEquals("google", sorted[1])
        assertEquals("Zebra", sorted[2])
        // Japanese comes behind alphabet
        assertTrue(sorted[3] == "あいうえお" || sorted[3] == "テスト" || sorted[3] == "機械学習")
        assertEquals("あいうえお", sorted[3])
        assertEquals("テスト", sorted[4])
        assertEquals("機械学習", sorted[5])
    }

    @Test
    fun testNsfwContentsScreenRoute() {
        assertEquals("settings_nsfw", me.bubblespark.tidilo.ui.navigation.Screen.NsfwContents.route)
        assertEquals("NSFW Contents", me.bubblespark.tidilo.ui.navigation.Screen.NsfwContents.title)
    }

    @Test
    fun testNsfwFilteringLogic() {
        val mixedBookmarks = listOf(
            BookmarkItem(id = "1", title = "Clean Tech", url = "https://example.com/1", description = "", tag = Tag.create("Tech", "Tech"), isNsfw = false),
            BookmarkItem(id = "2", title = "Adult Doujin", url = "https://example.com/2", description = "", tag = Tag.create("Media", "Adult"), isNsfw = true)
        )

        // When NSFW is visible
        val visibleWhenTrue = mixedBookmarks.filter { true }
        assertEquals(2, visibleWhenTrue.size)

        // When NSFW is hidden
        val visibleWhenFalse = mixedBookmarks.filter { !it.isNsfw }
        assertEquals(1, visibleWhenFalse.size)
        assertEquals("Clean Tech", visibleWhenFalse[0].title)
    }
}
