package me.bubblespark.tidilo.data.db

import androidx.sqlite.db.SimpleSQLiteQuery
import me.bubblespark.tidilo.data.Category
import me.bubblespark.tidilo.data.Subcategory

/** Compiles bound, literal substring predicates shared by results and tag aggregates. */
internal object BookmarkQueries {
    fun count(query: String, showNsfw: Boolean, domain: String? = null): SimpleSQLiteQuery {
        val (where, args) = predicate(query, showNsfw, domain, emptySet())
        return SimpleSQLiteQuery("SELECT COUNT(*) FROM bookmarks b WHERE $where", args.toTypedArray())
    }
    fun results(query: String, showNsfw: Boolean, domain: String? = null): SimpleSQLiteQuery {
        val (where, args) = predicate(query, showNsfw, domain, emptySet())
        return SimpleSQLiteQuery("SELECT b.* FROM bookmarks b WHERE $where ORDER BY b.createdAt DESC, b.id DESC", args.toTypedArray())
    }

    /** Counts occurrences over the AND-selected active set, without loading bookmark objects. */
    fun tags(selected: Set<String>, showNsfw: Boolean, kind: TagKind): SimpleSQLiteQuery {
        val (where, args) = predicate("", showNsfw, null, selected)
        val sql = when (kind) {
            TagKind.CATEGORY -> "SELECT COALESCE(b.category,'None') AS value, COUNT(*) AS count FROM bookmarks b WHERE $where AND b.searchCategory IS NOT NULL GROUP BY b.category, b.searchCategory"
            TagKind.SUBCATEGORY -> "SELECT COALESCE(b.subcategory,'None') AS value, COUNT(*) AS count FROM bookmarks b WHERE $where AND b.searchSubcategory IS NOT NULL GROUP BY b.subcategory, b.searchSubcategory"
            TagKind.ENTITY -> "SELECT e.value AS value, COUNT(*) AS count FROM bookmarks b JOIN bookmark_entities e ON e.bookmarkId=b.id WHERE $where GROUP BY e.value"
        }
        return SimpleSQLiteQuery(sql, args.toTypedArray())
    }

    private fun predicate(query: String, showNsfw: Boolean, domain: String?, selected: Set<String>): Pair<String, MutableList<Any>> {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<Any>()
        if (!showNsfw) clauses.add("b.isNsfw=0")
        if (domain != null) { clauses.add("b.domain=?"); args.add(domain) }
        for (tag in selected) {
            clauses.add("(b.searchCategory=? OR b.searchSubcategory=? OR EXISTS (SELECT 1 FROM bookmark_entities t WHERE t.bookmarkId=b.id AND t.searchValue=?))")
            repeat(3) { args.add(bookmarkSearchKey(tag)) }
        }
        for (token in query.trim().replace('\u3000', ' ').split(' ').filter { it.isNotBlank() }) {
            if (!token.startsWith('#')) {
                clauses.add("(instr(b.searchTitle,?)>0 OR instr(b.searchDescription,?)>0 OR instr(b.searchUrl,?)>0)")
                repeat(3) { args.add(bookmarkSearchKey(token)) }
                continue
            }
            val tag = token.removePrefix("#").trim()
            if (tag.isEmpty()) continue
            val alternatives = mutableListOf("instr(b.searchCategory,?)>0", "instr(b.searchSubcategory,?)>0",
                "EXISTS (SELECT 1 FROM bookmark_entities t WHERE t.bookmarkId=b.id AND instr(t.searchValue,?)>0)")
            repeat(3) { args.add(bookmarkSearchKey(tag)) }
            // Stored legacy IDs may be localized labels; resolve all recognized representations.
            for (category in Category.entries.filter { it.enLabel.contains(tag, true) || it.jaLabel.contains(tag, true) }) {
                for (value in listOf(category.id, category.enLabel, category.jaLabel).distinct()) {
                    alternatives.add("b.searchCategory=?"); args.add(bookmarkSearchKey(value))
                }
            }
            for (subcategory in Subcategory.entries.filter { it.enLabel.contains(tag, true) || it.jaLabel.contains(tag, true) }) {
                for (value in listOf(subcategory.id, subcategory.enLabel, subcategory.jaLabel).distinct()) {
                    alternatives.add("b.searchSubcategory=?"); args.add(bookmarkSearchKey(value))
                }
            }
            clauses.add(alternatives.joinToString(" OR ", "(", ")"))
        }
        return (if (clauses.isEmpty()) "1" else clauses.joinToString(" AND ")) to args
    }
}

internal enum class TagKind { CATEGORY, SUBCATEGORY, ENTITY }

/** Merges legacy spellings into the exact, case-sensitive buckets displayed by Tag.create. */
internal fun normalizeTagCounts(rows: List<TagCount>, kind: TagKind): List<TagCount> =
    if (kind == TagKind.ENTITY) rows else rows.groupBy {
        it.value.trim().removePrefix("#").trim().ifBlank { "None" }
    }.map { (value, counts) -> TagCount(value, counts.sumOf { it.count }) }
