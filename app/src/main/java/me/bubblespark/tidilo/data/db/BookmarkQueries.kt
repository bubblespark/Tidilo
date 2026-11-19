package me.bubblespark.tidilo.data.db

import androidx.sqlite.db.SimpleSQLiteQuery
import me.bubblespark.tidilo.data.BookmarkSource
import me.bubblespark.tidilo.data.Category
import me.bubblespark.tidilo.data.Subcategory

/** Compiles bound, literal substring predicates shared by results and tag aggregates. */
internal object BookmarkQueries {
    fun count(query: String, showPrivate: Boolean, domain: String? = null, source: BookmarkSource = BookmarkSource.ALL, rssFeedId: String? = null): SimpleSQLiteQuery {
        val (where, args) = predicate(BookmarkSearchTerms.parse(query), showPrivate, domain, emptySet(), source, rssFeedId)
        return SimpleSQLiteQuery("SELECT COUNT(*) FROM bookmarks b WHERE $where", args.toTypedArray())
    }
    fun results(query: String, showPrivate: Boolean, domain: String? = null, source: BookmarkSource = BookmarkSource.ALL, rssFeedId: String? = null): SimpleSQLiteQuery {
        return results(BookmarkSearchTerms.parse(query), showPrivate, domain, source, rssFeedId)
    }

    fun results(query: BookmarkSearchTerms, showPrivate: Boolean, domain: String? = null, source: BookmarkSource = BookmarkSource.ALL, rssFeedId: String? = null): SimpleSQLiteQuery {
        val (where, args) = predicate(query, showPrivate, domain, emptySet(), source, rssFeedId)
        return SimpleSQLiteQuery("SELECT b.* FROM bookmarks b WHERE $where ORDER BY b.createdAt DESC, b.id DESC", args.toTypedArray())
    }

    /** Counts occurrences over the AND-selected active set, without loading bookmark objects. */
    fun tags(selected: Set<String>, showPrivate: Boolean, kind: TagKind): SimpleSQLiteQuery {
        val (where, args) = predicate(BookmarkSearchTerms.parse(""), showPrivate, null, selected)
        val sql = when (kind) {
            TagKind.CATEGORY -> "SELECT COALESCE(b.category,'None') AS value, COUNT(*) AS count FROM bookmarks b WHERE $where AND b.searchCategory IS NOT NULL GROUP BY b.category, b.searchCategory"
            TagKind.SUBCATEGORY -> "SELECT COALESCE(b.subcategory,'None') AS value, COUNT(*) AS count FROM bookmarks b WHERE $where AND b.searchSubcategory IS NOT NULL GROUP BY b.subcategory, b.searchSubcategory"
            TagKind.ENTITY -> "SELECT e.value AS value, COUNT(*) AS count FROM bookmarks b JOIN bookmark_entities e ON e.bookmarkId=b.id WHERE $where GROUP BY e.value"
        }
        return SimpleSQLiteQuery(sql, args.toTypedArray())
    }

    private fun predicate(query: BookmarkSearchTerms, showPrivate: Boolean, domain: String?, selected: Set<String>, source: BookmarkSource = BookmarkSource.ALL, rssFeedId: String? = null): Pair<String, MutableList<Any>> {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<Any>()
        when (source) {
            BookmarkSource.MANUAL -> clauses.add("b.rssFeedId IS NULL")
            BookmarkSource.RSS -> clauses.add("b.rssFeedId IS NOT NULL")
            BookmarkSource.ALL -> Unit
        }
        if (!showPrivate) clauses.add("b.isPrivate=0")
        if (domain != null) { clauses.add("b.domain=?"); args.add(domain) }
        if (rssFeedId != null) { clauses.add("b.rssFeedId=?"); args.add(rssFeedId) }
        for (tag in selected) {
            clauses.add("(b.searchCategory=? OR b.searchSubcategory=? OR EXISTS (SELECT 1 FROM bookmark_entities t WHERE t.bookmarkId=b.id AND t.searchValue=?))")
            repeat(3) { args.add(bookmarkSearchKey(tag)) }
        }
        for (term in query.terms) {
            if (!term.isTag) {
                clauses.add("(instr(b.searchTitle,?)>0 OR instr(b.searchDescription,?)>0 OR instr(b.searchUrl,?)>0)")
                repeat(3) { args.add(term.key) }
                continue
            }
            val alternatives = mutableListOf("instr(b.searchCategory,?)>0", "instr(b.searchSubcategory,?)>0",
                "EXISTS (SELECT 1 FROM bookmark_entities t WHERE t.bookmarkId=b.id AND instr(t.searchValue,?)>0)")
            repeat(3) { args.add(term.key) }
            for (key in term.categoryAliases) {
                alternatives.add("b.searchCategory=?")
                args.add(key)
            }
            for (key in term.subcategoryAliases) {
                alternatives.add("b.searchSubcategory=?")
                args.add(key)
            }
            clauses.add(alternatives.joinToString(" OR ", "(", ")"))
        }
        return (if (clauses.isEmpty()) "1" else clauses.joinToString(" AND ")) to args
    }
}

internal enum class BookmarkMatchField { TITLE, SUMMARY, URL, CLASSIFICATION, ENTITY_TAGS }

internal data class BookmarkSearchTerm(
    val key: String,
    val isTag: Boolean = false,
    val categoryAliases: Set<String> = emptySet(),
    val subcategoryAliases: Set<String> = emptySet()
)

internal class BookmarkSearchTerms private constructor(val terms: List<BookmarkSearchTerm>) {
    fun matchingFields(bookmark: BookmarkEntity): Set<BookmarkMatchField> {
        if (terms.isEmpty()) return emptySet()
        val entityKeys = if (terms.any { it.isTag }) bookmark.entities.map(::bookmarkSearchKey) else emptyList()
        return buildSet {
            for (term in terms) {
                if (term.isTag) {
                    val category = bookmark.searchCategory
                    val subcategory = bookmark.searchSubcategory
                    if ((category != null && (category.contains(term.key) || category in term.categoryAliases)) ||
                        (subcategory != null && (subcategory.contains(term.key) || subcategory in term.subcategoryAliases))
                    ) {
                        add(BookmarkMatchField.CLASSIFICATION)
                    }
                    if (entityKeys.any { it.contains(term.key) }) add(BookmarkMatchField.ENTITY_TAGS)
                } else {
                    if (bookmark.searchTitle.contains(term.key)) add(BookmarkMatchField.TITLE)
                    if (bookmark.searchDescription.contains(term.key)) add(BookmarkMatchField.SUMMARY)
                    if (bookmark.searchUrl.contains(term.key)) add(BookmarkMatchField.URL)
                }
            }
        }
    }

    companion object {
        fun parse(query: String): BookmarkSearchTerms = BookmarkSearchTerms(
            query.trim().replace('\u3000', ' ').split(' ').mapNotNull { token ->
                if (token.isBlank()) return@mapNotNull null
                if (!token.startsWith('#')) return@mapNotNull BookmarkSearchTerm(bookmarkSearchKey(token))
                val tag = token.removePrefix("#").trim()
                if (tag.isEmpty()) return@mapNotNull null
                // Legacy rows can store the ID or either localized name.
                val categories = Category.entries.filter { it.enLabel.contains(tag, true) || it.jaLabel.contains(tag, true) }
                    .flatMap { listOf(it.id, it.enLabel, it.jaLabel) }
                    .mapTo(mutableSetOf(), ::bookmarkSearchKey)
                val subcategories = Subcategory.entries.filter { it.enLabel.contains(tag, true) || it.jaLabel.contains(tag, true) }
                    .flatMap { listOf(it.id, it.enLabel, it.jaLabel) }
                    .mapTo(mutableSetOf(), ::bookmarkSearchKey)
                BookmarkSearchTerm(bookmarkSearchKey(tag), true, categories, subcategories)
            }
        )
    }
}

internal enum class TagKind { CATEGORY, SUBCATEGORY, ENTITY }

/** Merges legacy spellings into the exact, case-sensitive buckets displayed by Tag.create. */
internal fun normalizeTagCounts(rows: List<TagCount>, kind: TagKind): List<TagCount> =
    if (kind == TagKind.ENTITY) rows else rows.groupBy {
        it.value.trim().removePrefix("#").trim().ifBlank { "None" }
    }.map { (value, counts) -> TagCount(value, counts.sumOf { it.count }) }
