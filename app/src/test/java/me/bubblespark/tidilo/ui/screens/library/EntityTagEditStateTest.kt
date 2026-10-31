package me.bubblespark.tidilo.ui.screens.library

import org.junit.Assert.*
import org.junit.Test

class EntityTagEditStateTest {
    @Test fun useExistingAddsCanonicalSpellingWithoutRename() {
        assertEquals(EntityTagDraft(listOf("OpenAI")), applyEntityTagChoice(EntityTagDraft(emptyList()), "open-ai", "OpenAI", false))
    }
    @Test fun replacementRewritesDraftAndStagesSpelling() {
        assertEquals(EntityTagDraft(listOf("open-ai", "Kotlin"), mapOf("openai" to "open-ai")),
            applyEntityTagChoice(EntityTagDraft(listOf("OpenAI", "Kotlin")), "open-ai", "OpenAI", true))
    }
    @Test fun conflictUsesPendingThenLibraryThenDraft() {
        val draft = EntityTagDraft(listOf("OpenAI"), mapOf("openai" to "OPEN.AI"))
        assertEquals("OPEN.AI", entityTagConflict("open-ai", draft, mapOf("openai" to "OpenAI"))?.existing)
        assertEquals("OpenAI", entityTagConflict("open-ai", EntityTagDraft(listOf("OpenAI")), emptyMap())?.existing)
        assertNull(entityTagConflict("OpenAI", EntityTagDraft(listOf("OpenAI")), emptyMap()))
    }
    @Test fun laterChoiceWinsAndUseExistingPreservesPendingRename() {
        val draft = EntityTagDraft(listOf("OpenAI"), mapOf("openai" to "OpenAI"))
        assertEquals(mapOf("openai" to "OPEN-AI"), applyEntityTagChoice(draft, "OPEN-AI", "OpenAI", true).replacements)
        assertEquals(draft, applyEntityTagChoice(draft, "open-ai", "OpenAI", false))
    }
    @Test fun fullDraftCanReplaceButCannotAddEleventhKey() {
        val draft = EntityTagDraft(listOf("OpenAI") + (1..9).map { "Tag$it" })
        assertEquals("open-ai", applyEntityTagChoice(draft, "open-ai", "OpenAI", true).entities.first())
        assertEquals(draft, applyEntityTagChoice(draft, "Another", null, false))
    }
    @Test fun emptySkeletonCannotBeAddedOrStaged() {
        val draft = EntityTagDraft(emptyList())
        assertEquals(draft, applyEntityTagChoice(draft, "# -_. ", null, true))
        assertNull(entityTagConflict("_ - .", draft, emptyMap()))
    }
    @Test fun rawInputIsCleanedOnceAndStoredLeadingHashIsPreserved() {
        val draft = EntityTagDraft(listOf("#C", "C"))
        assertNull(entityTagConflict("##C", draft, mapOf("#c" to "#C", "c" to "C")))
        assertEquals(draft, applyEntityTagChoice(draft, "##C", "#C", false))
        assertEquals(listOf("#C"), applyEntityTagChoice(EntityTagDraft(emptyList()), "##C", null, false).entities)
    }
}
