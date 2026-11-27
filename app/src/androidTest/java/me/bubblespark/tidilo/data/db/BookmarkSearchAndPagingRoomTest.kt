package me.bubblespark.tidilo.data.db

import androidx.paging.AsyncPagingDataDiffer
import androidx.paging.LoadState
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListUpdateCallback
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.bubblespark.tidilo.data.Category
import me.bubblespark.tidilo.data.EntityTagPolicy
import me.bubblespark.tidilo.data.Subcategory
import me.bubblespark.tidilo.ui.screens.library.BookmarkItem
import me.bubblespark.tidilo.ui.screens.library.Tag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BookmarkSearchAndPagingRoomTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun inMemoryDatabase(): AppDatabase =
        Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()

    private suspend fun results(db: AppDatabase, query: String, showPrivate: Boolean = true): List<BookmarkEntity> {
        val page = db.bookmarkDao().search(BookmarkQueries.results(query, showPrivate)).load(
            PagingSource.LoadParams.Refresh(null, 30, false)
        )
        assertTrue(page.toString(), page is PagingSource.LoadResult.Page)
        return (page as PagingSource.LoadResult.Page).data
    }

    @Test
    fun boundedPagesReloadInReverseAndOffPageDetailsRemainAvailable() = runBlocking {
        val db = inMemoryDatabase()
        try {
            db.bookmarkDao().insertAll((0 until 420).map {
                BookmarkEntity(
                    it.toString().padStart(4, '0'), "Bookmark $it", "https://example.com/$it", "",
                    createdAt = (420 - it).toLong(), updatedAt = 1
                )
            })
            withContext(Dispatchers.Main) {
                val differ = AsyncPagingDataDiffer(
                    object : DiffUtil.ItemCallback<BookmarkEntity>() {
                        override fun areItemsTheSame(a: BookmarkEntity, b: BookmarkEntity) = a.id == b.id
                        override fun areContentsTheSame(a: BookmarkEntity, b: BookmarkEntity) = a == b
                    },
                    object : ListUpdateCallback {
                        override fun onInserted(position: Int, count: Int) = Unit
                        override fun onRemoved(position: Int, count: Int) = Unit
                        override fun onMoved(fromPosition: Int, toPosition: Int) = Unit
                        override fun onChanged(position: Int, count: Int, payload: Any?) = Unit
                    }
                )
                val collection = launch {
                    Pager(PagingConfig(30, 10, false, 60, 150)) { db.bookmarkDao().timeline(true) }
                        .flow.collectLatest { differ.submitData(it) }
                }
                suspend fun awaitSnapshot(predicate: () -> Boolean) {
                    if (predicate()) return
                    withTimeout(5000) {
                        differ.onPagesUpdatedFlow.first { predicate() }
                    }
                }
                try {
                    awaitSnapshot { differ.itemCount >= 60 }
                    repeat(8) {
                        val last = differ.snapshot().items.last().id
                        differ.getItem(differ.itemCount - 1)
                        awaitSnapshot { differ.snapshot().items.last().id > last }
                        assertTrue("Retained pages must stay bounded", differ.itemCount <= 150)
                    }
                    assertTrue(differ.snapshot().items.first().id > "0000")
                    assertEquals("0000", db.bookmarkDao().observeById("0000").first()?.id)
                    db.bookmarkDao().updateNote("0000", "Off-page update", 2)
                    assertEquals("Off-page update", db.bookmarkDao().getBookmarkById("0000")?.note)
                    withTimeout(5000) { differ.loadStateFlow.first { it.refresh is LoadState.NotLoading } }
                    var attempts = 0
                    while (differ.snapshot().items.firstOrNull()?.id != "0000" && attempts++ < 20) {
                        val first = differ.snapshot().items.first().id
                        differ.getItem(0)
                        awaitSnapshot { differ.snapshot().items.firstOrNull()?.id?.let { it < first } == true }
                    }
                    assertEquals("0000", differ.snapshot().items.first().id)
                    assertEquals("Off-page update", differ.snapshot().items.first().note)
                } finally { collection.cancelAndJoin() }
            }
        } finally { db.close() }
    }

    @Test
    fun unicodeCaseKeysMatchTheAndroidRuntime() {
        for (point in 0..Character.MAX_CODE_POINT) {
            if (point in 0xD800..0xDFFF) continue
            val original = String(Character.toChars(point))
            for (variant in intArrayOf(Character.toUpperCase(point), Character.toLowerCase(point))) {
                val other = String(Character.toChars(variant))
                assertEquals(
                    "Unicode point $point versus $variant",
                    original.equals(other, ignoreCase = true), bookmarkSearchKey(original) == bookmarkSearchKey(other)
                )
            }
        }
    }

    @Test
    fun cleanedActiveTagsMatchRenderedCardsAndCanonicalizeDuplicates() = runBlocking {
        val db = inMemoryDatabase()
        try {
            val rows = listOf(
                BookmarkEntity("none", "No tags", "https://example.com/n", "", createdAt = 1, updatedAt = 1),
                BookmarkEntity("skipped", "Skipped", "https://example.com/s", "", createdAt = 2, updatedAt = 2),
                BookmarkEntity(
                    "raw", "Raw tags", "https://example.com/r", "", " #Article ", " #Tech ",
                    EntityTagPolicy.normalizeEntities(listOf(" #Kotlin ", " ", "Kotlin")), 3, 3
                ),
                BookmarkEntity("clean", "Clean tags", "https://example.com/c", "", "Article", "Life", createdAt = 4, updatedAt = 4),
                BookmarkEntity("blank", "Blank tags", "https://example.com/b", "", " # ", " ", createdAt = 5, updatedAt = 5)
            )
            db.bookmarkDao().insertAll(rows)
            for (query in listOf("#None", "#Kotlin", "#Article", "#Tech", "#記事", "#技術", "#Kotlin #Tech")) {
                assertEquals(
                    query, expectedSearchIds(rows.map { it.toBookmarkItem() }, query),
                    results(db, query).map { it.id }.toSet()
                )
            }
            val counts = db.bookmarkDao().tagCounts(BookmarkQueries.tags(setOf("Tech"), true, TagKind.ENTITY)).first()
            assertEquals(listOf(TagCount("Kotlin", 1)), counts)
            val categoryCounts = normalizeTagCounts(
                db.bookmarkDao().tagCounts(BookmarkQueries.tags(emptySet(), true, TagKind.CATEGORY)).first(),
                TagKind.CATEGORY
            )
            assertEquals(mapOf("None" to 1, "Article" to 2), categoryCounts.associate { it.value to it.count })
            assertEquals(" #Article ", db.bookmarkDao().getBookmarkById("raw")?.category)
            assertEquals(listOf("Kotlin"), db.bookmarkDao().getBookmarkById("raw")?.entities)
        } finally { db.close() }
    }

    @Test
    fun prereleaseDerivedFieldsAreRepairedOnceWithoutChangingOriginalColumns() = runBlocking {
        val name = "issue81-derived-repair-${UUID.randomUUID()}.db"
        try {
            var db = openMigratedDatabase(context, name)
            try {
                db.bookmarkDao().insertOrUpdate(
                    BookmarkEntity(
                        "raw", "Kept", "https://example.com", "",
                        " #Article ", " #Tech ", listOf("Kotlin", "Kotlin"), 1, 1, note = "preserved"
                    )
                )
                val sql = db.openHelper.writableDatabase
                val originalColumns = columns(sql, "bookmarks").filterNot { it == "domain" || it.startsWith("search") }
                val before = fingerprint(sql, "bookmarks", originalColumns)
                sql.execSQL("UPDATE bookmarks SET searchCategory=' #article ',searchSubcategory=' #tech '")
                sql.execSQL("UPDATE bookmark_entities SET value=' #Kotlin ',searchValue=' #kotlin '")
                sql.execSQL("PRAGMA application_id=0")
                db.close()
                db = openMigratedDatabase(context, name)
                assertEquals(listOf("raw"), results(db, "#記事 #技術 #Kotlin").map { it.id })
                assertEquals(
                    listOf(TagCount("Kotlin", 1)),
                    db.bookmarkDao().tagCounts(BookmarkQueries.tags(setOf("Tech"), true, TagKind.ENTITY)).first()
                )
                assertEquals(before, fingerprint(db.openHelper.readableDatabase, "bookmarks", originalColumns))
                db.openHelper.readableDatabase.query("PRAGMA application_id").use {
                    it.moveToFirst(); assertEquals(BOOKMARK_DERIVED_FORMAT, it.getInt(0))
                }
                // A valid marker skips reconstruction on subsequent opens.
                db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_rebuild BEFORE DELETE ON bookmark_entities BEGIN SELECT RAISE(ABORT,'unexpected rebuild'); END")
                db.close()
                db = openMigratedDatabase(context, name)
                assertEquals(1, db.bookmarkDao().getCount())
            } finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test
    fun sqliteSearchAndAggregationMatchExistingRulesAndWritesStaySynchronized() = runBlocking {
        val db = inMemoryDatabase()
        try {
            val rows = listOf(
                BookmarkEntity("a", "İstanbul 100%_ \uD801\uDC00", "https://www.example.com/a", "記事 École", "Article", "Tech", listOf("Kotlin", "Kotlin", "σ"), 1, 1),
                BookmarkEntity("b", "Other", "https://example.com/b", "description", "Tool", "Life", listOf("Kot", "lin"), 1, 1),
                BookmarkEntity("c", "istanbul", "not a valid URL", "記事", "Article", "Tech", listOf("Kotlin"), 2, 2, isPrivate = true)
            )
            db.bookmarkDao().insertAll(rows)
            val queries = listOf(
                "", "#", "Kotlin", "#Kotlin", "#記事", "#技術", "İSTANBUL", "istanbul",
                "100%_", "%", "_", "\uD801\uDC00", "\uD801\uDC28", "#σ", "#Kotlin\u3000École", "#Article #Tech", "#lin", "#Kot #lin"
            )
            for (showPrivate in listOf(false, true)) {
                for (query in queries) {
                    val visible = rows.filter { showPrivate || !it.isPrivate }.map { it.toBookmarkItem() }
                    val expected = expectedSearchIds(visible, query)
                    assertEquals("query=$query visible=$showPrivate", expected, results(db, query, showPrivate).map { it.id }.toSet())
                    assertEquals(
                        "Count must include off-page and domainless results", expected.size,
                        db.bookmarkDao().observeCount(BookmarkQueries.count(query, showPrivate)).first()
                    )
                }
            }
            val mixedQuery = "École　#Kot #記事"
            val matched = results(db, mixedQuery).single()
            assertEquals("a", matched.id)
            assertEquals(
                setOf(BookmarkMatchField.SUMMARY, BookmarkMatchField.CLASSIFICATION, BookmarkMatchField.ENTITY_TAGS),
                BookmarkSearchTerms.parse(mixedQuery).matchingFields(matched)
            )
            assertEquals(1, db.bookmarkDao().observeCount(BookmarkQueries.count(mixedQuery, true)).first())
            assertEquals(
                setOf(BookmarkMatchField.URL),
                BookmarkSearchTerms.parse("www.example").matchingFields(results(db, "www.example").single())
            )
            assertTrue(BookmarkSearchTerms.parse("#").matchingFields(matched).isEmpty())
            assertEquals(listOf("c", "b", "a"), results(db, "").map { it.id })
            val counts = db.bookmarkDao().tagCounts(BookmarkQueries.tags(setOf("Tech"), false, TagKind.ENTITY)).first()
            assertEquals(1, counts.single { it.value == "Kotlin" }.count)
            assertEquals(listOf("example.com"), db.bookmarkDao().distinctDomains())
            db.bookmarkDao().updateDescriptionAndTag("a", "New description", Tag("Tool", "Life", listOf("Updated")), 3)
            assertTrue(results(db, "#Kotlin", false).isEmpty())
            assertEquals(listOf("a"), results(db, "#Updated New", false).map { it.id })
            db.bookmarkDao().deleteById("a")
            assertTrue(db.bookmarkDao().tagCounts(BookmarkQueries.tags(emptySet(), false, TagKind.ENTITY)).first().none { it.value == "Updated" })
            db.bookmarkDao().insertOrUpdate(rows.first().copy(title = "Changed", entities = listOf("Restored")))
            assertEquals(listOf("a"), results(db, "Changed #Restored").map { it.id })
            assertTrue(results(db, "100%_").isEmpty())
            try {
                db.withTransaction {
                    db.bookmarkDao().insertOrUpdate(rows.first().copy(title = "Rolled back", entities = listOf("Lost")))
                    error("Injected write failure")
                }
                fail("Transaction should fail")
            } catch (_: IllegalStateException) {
                assertEquals(listOf("a"), results(db, "Changed #Restored").map { it.id })
                assertTrue(results(db, "#Lost").isEmpty())
            }
        } finally { db.close() }
    }

    /** In-memory reference for the SQL search semantics: AND of words and `#` tags, bilingual taxonomy labels. */
    private fun expectedSearchIds(items: List<BookmarkItem>, rawQuery: String): Set<String> = matchingItems(items, rawQuery).map { it.id }.toSet()

    private fun matchingItems(items: List<BookmarkItem>, rawQuery: String): List<BookmarkItem> {
        if (rawQuery.isBlank()) return items

        // Delimit by half-width space (normalizing full-width spaces)
        val tokens = rawQuery.trim()
            .replace("　", " ")
            .split(" ")
            .filter { it.isNotBlank() }

        if (tokens.isEmpty()) return items

        return items.filter { item ->
            // Match all tokens (AND search)
            tokens.all { token ->
                if (token.startsWith("#")) {
                    // Tag search: strip '#' and search category, subcategory, or entity
                    val tagQuery = token.removePrefix("#").trim()
                    if (tagQuery.isEmpty()) {
                        true // Allow all tags if solitary '#' is entered
                    } else {
                        // Category (English ID or Japanese label)
                        val catMatch = item.tag.category?.contains(tagQuery, ignoreCase = true) == true ||
                            (item.tag.category?.let { Category.fromId(it) }?.let {
                                it.jaLabel.contains(tagQuery, ignoreCase = true) ||
                                    it.enLabel.contains(tagQuery, ignoreCase = true)
                            } ?: false)

                        // Subcategory (English ID or Japanese label)
                        val subMatch = item.tag.subcategory?.contains(tagQuery, ignoreCase = true) == true ||
                            (item.tag.subcategory?.let { Subcategory.fromId(it) }?.let {
                                it.jaLabel.contains(tagQuery, ignoreCase = true) ||
                                    it.enLabel.contains(tagQuery, ignoreCase = true)
                            } ?: false)

                        // Entity category (specific keyword list)
                        val entityMatch = item.tag.entities.any { entity ->
                            entity.contains(tagQuery, ignoreCase = true)
                        }

                        catMatch || subMatch || entityMatch
                    }
                } else {
                    // Word search: Title, Description, URL
                    item.title.contains(token, ignoreCase = true) ||
                        item.description.contains(token, ignoreCase = true) ||
                        item.url.contains(token, ignoreCase = true)
                }
            }
        }
    }
}
