package me.bubblespark.tidilo.data

import org.junit.Assert.*
import org.junit.Test

class EntityTagPolicyTest {

    @Test
    fun maxEntitiesConstantIsTen() {
        assertEquals(10, EntityTagPolicy.MAX_ENTITIES)
    }

    @Test
    fun cleanTrimsWhitespaceAndStripsSingleLeadingHash() {
        assertEquals("android", EntityTagPolicy.clean("android"))
        assertEquals("android", EntityTagPolicy.clean("  android  "))
        assertEquals("compose", EntityTagPolicy.clean("#compose"))
        assertEquals("kotlin", EntityTagPolicy.clean("  #kotlin  "))
        assertEquals("c#", EntityTagPolicy.clean("c#"))
        assertEquals("#tag", EntityTagPolicy.clean("##tag"))
        assertNull(EntityTagPolicy.clean(""))
        assertNull(EntityTagPolicy.clean("   "))
        assertNull(EntityTagPolicy.clean("#"))
        assertNull(EntityTagPolicy.clean("  #  "))
        assertNull(EntityTagPolicy.clean(null))
    }

    @Test
    fun normalizeReturnsCleanedStringOrEmpty() {
        assertEquals("kotlin", EntityTagPolicy.normalize("  #kotlin  "))
        assertEquals("", EntityTagPolicy.normalize("   "))
        assertEquals("", EntityTagPolicy.normalize("#"))
    }

    @Test
    fun addEntityIgnoresBlanksAndAddsValidEntity() {
        val initial = listOf("Kotlin")
        assertSame(initial, EntityTagPolicy.addEntity(initial, ""))
        assertSame(initial, EntityTagPolicy.addEntity(initial, "   "))
        assertSame(initial, EntityTagPolicy.addEntity(initial, "#"))
        assertSame(initial, EntityTagPolicy.addEntity(initial, "  #  "))

        val updated = EntityTagPolicy.addEntity(initial, " #Android ")
        assertEquals(listOf("Kotlin", "Android"), updated)
    }

    @Test
    fun addEntityDeduplicatesCaseInsensitivelyPreservingFirstSpellingAndOrder() {
        val initial = listOf("Kotlin", "Android")
        val duplicate1 = EntityTagPolicy.addEntity(initial, "kotlin")
        val duplicate2 = EntityTagPolicy.addEntity(initial, "#ANDROID")
        val duplicate3 = EntityTagPolicy.addEntity(initial, "  #KoTLiN  ")

        assertEquals(listOf("Kotlin", "Android"), duplicate1)
        assertEquals(listOf("Kotlin", "Android"), duplicate2)
        assertEquals(listOf("Kotlin", "Android"), duplicate3)

        val reversedInitial = listOf("android")
        val reversedUpdated = EntityTagPolicy.addEntity(reversedInitial, "Android")
        assertEquals(listOf("android"), reversedUpdated)
    }

    @Test
    fun addEntityCappedAtMaxEntities() {
        val tenEntities = (1..10).map { "Tag$it" }
        assertEquals(10, tenEntities.size)

        val rejected = EntityTagPolicy.addEntity(tenEntities, "Tag11")
        assertEquals(tenEntities, rejected)

        // Adding an existing entity when full does not mutate or exceed cap
        val existing = EntityTagPolicy.addEntity(tenEntities, "tag1")
        assertEquals(tenEntities, existing)
    }

    @Test
    fun toggleEntityAddsWhenAbsentAndRemovesWhenPresent() {
        val initial = listOf("Kotlin", "Compose")

        // Removing existing entity (case-insensitive)
        val removed = EntityTagPolicy.toggleEntity(initial, "kotlin")
        assertEquals(listOf("Compose"), removed)

        // Adding new entity
        val added = EntityTagPolicy.toggleEntity(initial, "#Android")
        assertEquals(listOf("Kotlin", "Compose", "Android"), added)

        // Ignoring blanks
        val blankUnchanged = EntityTagPolicy.toggleEntity(initial, "   ")
        assertEquals(initial, blankUnchanged)

        // At capacity: toggling absent does nothing
        val full = (1..10).map { "Tag$it" }
        val overflow = EntityTagPolicy.toggleEntity(full, "Tag11")
        assertEquals(full, overflow)

        // At capacity: toggling present successfully removes
        val reduced = EntityTagPolicy.toggleEntity(full, "tag10")
        assertEquals((1..9).map { "Tag$it" }, reduced)
    }

    @Test
    fun normalizeEntitiesCleansDeduplicatesAndCaps() {
        val raw = listOf(
            "  #Kotlin  ",
            "kotlin",
            "",
            "   ",
            "#",
            "Compose",
            "#compose",
            "Android",
            "ANDROID"
        ) + (1..15).map { "Topic$it" }

        val normalized = EntityTagPolicy.normalizeEntities(raw)

        val expected = listOf("Kotlin", "Compose", "Android") + (1..7).map { "Topic$it" }
        assertEquals(10, normalized.size)
        assertEquals(expected, normalized)
    }

    @Test
    fun separatorOnlyEntitiesAreIgnored() {
        for (value in listOf("._-", " # . _ - ", "．＿－")) {
            assertNull(EntityTagPolicy.clean(value))
            assertEquals("", EntityTagPolicy.normalize(value))
            assertEquals(emptyList<String>(), EntityTagPolicy.normalizeEntities(listOf(value)))
            assertEquals(listOf("Kotlin"), EntityTagPolicy.addEntity(listOf("Kotlin"), value))
            assertEquals(listOf("Kotlin"), EntityTagPolicy.toggleEntity(listOf("Kotlin"), value))
        }
    }

    @Test
    fun skeletonVariantsKeepFirstSpellingAndPosition() {
        assertEquals(
            listOf("Jetpack Compose", "Node.js", "C++", "C#"),
            EntityTagPolicy.normalizeEntities(listOf(" #Jetpack Compose ", "Node.js", "JETPACK_COMPOSE", "NodeJS", "C++", "C#", "Ｊｅｔｐａｃｋ－Ｃｏｍｐｏｓｅ"))
        )
        assertEquals(listOf("Jetpack Compose", "Node.js"), EntityTagPolicy.addEntity(listOf("Jetpack Compose", "Node.js"), "jetpack-compose"))
    }

    @Test
    fun duplicatesDoNotConsumeDistinctEntityCapacity() {
        val raw = listOf("Jetpack Compose", "jetpack-compose", "JETPACK_COMPOSE") + (1..10).map { "Tag$it" }
        assertEquals(listOf("Jetpack Compose") + (1..9).map { "Tag$it" }, EntityTagPolicy.normalizeEntities(raw))
    }

    @Test
    fun togglingVariantRemovesEveryMatchingSkeleton() {
        assertEquals(listOf("Kotlin"), EntityTagPolicy.toggleEntity(listOf("Node.js", "Kotlin", "NODE_JS"), "ＮｏｄｅＪＳ"))
    }
}
