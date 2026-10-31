package me.bubblespark.tidilo.data

import me.bubblespark.tidilo.data.db.bookmarkSearchKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class EntitySkeletonKeyTest {
    @Test
    fun formattingVariantsShareIdentity() {
        for (value in listOf("Jetpack Compose", "JetpackCompose", "jetpack-compose", "JETPACK_COMPOSE", "Ｊｅｔｐａｃｋ　Ｃｏｍｐｏｓｅ", "ＪＥＴＰＡＣＫ＿ＣＯＭＰＯＳＥ", "Ｊｅｔｐａｃｋ－Ｃｏｍｐｏｓｅ")) {
            assertEquals(value, "jetpackcompose", entitySkeletonKey(value))
        }
        assertEquals("nodejs", entitySkeletonKey("Node.js"))
        assertEquals("nodejs", entitySkeletonKey("NodeJS"))
    }

    @Test
    fun removesWhitespaceAndOnlySupportedSeparators() {
        assertEquals("jetpackcompose", entitySkeletonKey("Jet\tpack\nCom\u00a0po\u3000se"))
        assertEquals("", entitySkeletonKey(" ._-\t\n\u00a0\u3000"))
        assertEquals("c++", entitySkeletonKey("C++"))
        assertEquals("c#", entitySkeletonKey("C#"))
        assertNotEquals(entitySkeletonKey("C++"), entitySkeletonKey("C#"))
        assertNotEquals(entitySkeletonKey("JS"), entitySkeletonKey("JavaScript"))
        assertEquals("a/b:c", entitySkeletonKey("A/B:C"))
    }

    @Test
    fun followsSearchCaseFoldingWithoutChangingSearchNormalization() {
        for (value in listOf("I", "İ", "ı", "i")) assertEquals("i", entitySkeletonKey(value))
        for (value in listOf("Σ", "σ", "ς")) assertEquals("σ", entitySkeletonKey(value))
        assertEquals("jetpack compose._-", bookmarkSearchKey("Jetpack Compose._-"))
        assertEquals("ａｂｃ", bookmarkSearchKey("ＡＢＣ"))
        assertEquals("i", bookmarkSearchKey("İ"))
        assertEquals("σ", bookmarkSearchKey("ς"))
    }
}
