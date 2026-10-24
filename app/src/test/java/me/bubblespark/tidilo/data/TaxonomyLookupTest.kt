package me.bubblespark.tidilo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TaxonomyLookupTest {

    @Test
    fun testCategoryFromId_caseInsensitiveAndLabelLookups() {
        // By ID (exact, lower, upper)
        assertEquals(Category.ARTICLE, Category.fromId("Article"))
        assertEquals(Category.ARTICLE, Category.fromId("article"))
        assertEquals(Category.ARTICLE, Category.fromId("ARTICLE"))

        assertEquals(Category.MEDIA, Category.fromId("media"))
        assertEquals(Category.TOOL, Category.fromId("TOOL"))
        assertEquals(Category.COMMERCE, Category.fromId("commerce"))
        assertEquals(Category.COMMUNITY, Category.fromId("community"))
        assertEquals(Category.OFFICIAL, Category.fromId("official"))
        assertEquals(Category.REFERENCE, Category.fromId("reference"))

        // By Japanese and English labels
        assertEquals(Category.ARTICLE, Category.fromId("記事"))
        assertEquals(Category.MEDIA, Category.fromId("動画・作品"))
        assertEquals(Category.TOOL, Category.fromId("ツール"))
        assertEquals(Category.COMMERCE, Category.fromId("ショップ"))
        assertEquals(Category.COMMUNITY, Category.fromId("コミュニティ"))
        assertEquals(Category.OFFICIAL, Category.fromId("公式・紹介"))
        assertEquals(Category.REFERENCE, Category.fromId("資料・情報"))

        // Unknown
        assertNull(Category.fromId("unknown_category"))
        assertNull(Category.fromId(""))
    }

    @Test
    fun testSubcategoryFromId_caseInsensitiveAndLabelLookups() {
        // By ID (exact, lower, upper)
        assertEquals(Subcategory.TECH, Subcategory.fromId("Tech"))
        assertEquals(Subcategory.TECH, Subcategory.fromId("tech"))
        assertEquals(Subcategory.TECH, Subcategory.fromId("TECH"))

        assertEquals(Subcategory.BUSINESS, Subcategory.fromId("business"))
        assertEquals(Subcategory.SOCIETY, Subcategory.fromId("society"))
        assertEquals(Subcategory.LIFE, Subcategory.fromId("life"))
        assertEquals(Subcategory.FOOD, Subcategory.fromId("food"))
        assertEquals(Subcategory.TRAVEL, Subcategory.fromId("travel"))
        assertEquals(Subcategory.GAME, Subcategory.fromId("game"))
        assertEquals(Subcategory.ENTERTAINMENT, Subcategory.fromId("entertainment"))
        assertEquals(Subcategory.HOBBY, Subcategory.fromId("hobby"))
        assertEquals(Subcategory.ADULT, Subcategory.fromId("adult"))
        assertEquals(Subcategory.EDUCATION, Subcategory.fromId("education"))

        // By Japanese and English labels
        assertEquals(Subcategory.TECH, Subcategory.fromId("技術・IT"))
        assertEquals(Subcategory.BUSINESS, Subcategory.fromId("ビジネス"))
        assertEquals(Subcategory.SOCIETY, Subcategory.fromId("ニュース・社会"))
        assertEquals(Subcategory.LIFE, Subcategory.fromId("暮らし・健康"))
        assertEquals(Subcategory.FOOD, Subcategory.fromId("グルメ・料理"))
        assertEquals(Subcategory.TRAVEL, Subcategory.fromId("旅行・交通"))
        assertEquals(Subcategory.GAME, Subcategory.fromId("ゲーム"))
        assertEquals(Subcategory.ENTERTAINMENT, Subcategory.fromId("エンタメ"))
        assertEquals(Subcategory.HOBBY, Subcategory.fromId("趣味・スポーツ"))
        assertEquals(Subcategory.ADULT, Subcategory.fromId("大人の嗜好"))
        assertEquals(Subcategory.EDUCATION, Subcategory.fromId("教育"))

        // Unknown
        assertNull(Subcategory.fromId("unknown_subcategory"))
        assertNull(Subcategory.fromId(""))
    }

    @Test
    fun testToLocalized_booleanOverload() {
        assertEquals("記事", Category.toLocalized("Article", isJapanese = true))
        assertEquals("Article", Category.toLocalized("Article", isJapanese = false))
        assertEquals("FallbackCat", Category.toLocalized("FallbackCat", isJapanese = true))

        assertEquals("技術・IT", Subcategory.toLocalized("Tech", isJapanese = true))
        assertEquals("Tech", Subcategory.toLocalized("Tech", isJapanese = false))
        assertEquals("FallbackSub", Subcategory.toLocalized("FallbackSub", isJapanese = true))
    }
}
