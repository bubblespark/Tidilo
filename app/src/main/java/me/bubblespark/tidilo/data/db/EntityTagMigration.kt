package me.bubblespark.tidilo.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.bubblespark.tidilo.data.EntityTagPolicy
import me.bubblespark.tidilo.data.entitySkeletonKey

internal data class LegacyEntitySpelling(val bookmarkId: String, val createdAt: Long, val value: String)

/** Counts each cleaned spelling at most once per bookmark, before applying any entity limit. */
internal fun selectLegacyEntitySpellings(rows: Iterable<LegacyEntitySpelling>): Map<String, String> {
    val votes = mutableMapOf<String, MutableMap<String, Long>>()
    for (row in rows) {
        val value = EntityTagPolicy.clean(row.value) ?: continue
        val bookmarks = votes.getOrPut(value) { mutableMapOf() }
        bookmarks[row.bookmarkId] = minOf(bookmarks[row.bookmarkId] ?: Long.MAX_VALUE, row.createdAt)
    }
    return selectRankedSpellings(votes.map { (value, bookmarks) ->
        LegacySpellingRank(value, bookmarks.size, bookmarks.values.min())
    })
}

private fun compareBinarySpellings(left: String, right: String): Int {
    val leftPoints = left.codePoints().iterator()
    val rightPoints = right.codePoints().iterator()
    while (leftPoints.hasNext() && rightPoints.hasNext()) {
        val compared = leftPoints.nextInt().compareTo(rightPoints.nextInt())
        if (compared != 0) return compared
    }
    return leftPoints.hasNext().compareTo(rightPoints.hasNext())
}

private data class LegacySpellingRank(val value: String, val count: Int, val oldest: Long)

private fun selectRankedSpellings(ranks: Iterable<LegacySpellingRank>): Map<String, String> {
    val comparator = compareByDescending<LegacySpellingRank> { it.count }
        .thenBy { it.oldest }.thenComparator { left, right ->
            // SQLite BINARY compares UTF-8 bytes, equivalent to Unicode code-point order.
            compareBinarySpellings(left.value, right.value)
        }
    val selected = mutableMapOf<String, LegacySpellingRank>()
    for (rank in ranks) {
        val key = entitySkeletonKey(rank.value)
        val current = selected[key]
        if (current == null || comparator.compare(rank, current) < 0) selected[key] = rank
    }
    return selected.mapValues { it.value.value }
}

/** Elects active-library spellings only during upgrade; ordinary opens never repeat the election. */
internal val entityTagMigration13To14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE bookmark_entities ADD COLUMN skeletonKey TEXT NOT NULL DEFAULT ''")
        db.execSQL("CREATE INDEX index_bookmark_entities_skeletonKey ON bookmark_entities(skeletonKey)")
        // Keep frequency aggregation in SQLite rather than retaining every bookmark's entities.
        db.execSQL("CREATE TEMP TABLE entity_spelling_votes (value TEXT NOT NULL PRIMARY KEY, uses INTEGER NOT NULL, oldest INTEGER NOT NULL)")
        try {
            val vote = db.compileStatement("INSERT INTO entity_spelling_votes(value,uses,oldest) VALUES (?,1,?) ON CONFLICT(value) DO UPDATE SET uses=uses+1,oldest=MIN(oldest,excluded.oldest)")
            try {
                forEachLegacyEntityBatch(db) { rows ->
                    for (row in rows) {
                        val values = Converters().toStringList(row.entities).mapNotNull(EntityTagPolicy::clean).toSet()
                        for (value in values) {
                            vote.clearBindings()
                            vote.bindString(1, value)
                            vote.bindLong(2, row.createdAt)
                            vote.executeInsert()
                        }
                    }
                }
            } finally { vote.close() }
            val ranks = sequence {
                db.query("SELECT value,uses,oldest FROM entity_spelling_votes").use { cursor ->
                    while (cursor.moveToNext()) yield(LegacySpellingRank(cursor.getString(0), cursor.getInt(1), cursor.getLong(2)))
                }
            }
            val spellings = selectRankedSpellings(ranks.asIterable())
            val update = db.compileStatement("UPDATE bookmarks SET entities=? WHERE id=?")
            try {
                forEachLegacyEntityBatch(db) { rows ->
                    for (row in rows) {
                        val canonical = Converters().toStringList(row.entities).mapNotNull { raw ->
                            EntityTagPolicy.clean(raw)?.let { spellings[entitySkeletonKey(it)] }
                        }
                        val entities = EntityTagPolicy.normalizeEntities(canonical)
                        update.clearBindings()
                        update.bindString(1, Converters().fromStringList(entities))
                        update.bindString(2, row.id)
                        update.executeUpdateDelete()
                    }
                }
            } finally { update.close() }
            rebuildBookmarkDerivedData(db)
        } finally { db.execSQL("DROP TABLE entity_spelling_votes") }
    }
}

private data class LegacyEntityRow(val id: String, val createdAt: Long, val entities: String)

private fun forEachLegacyEntityBatch(db: SupportSQLiteDatabase, consume: (List<LegacyEntityRow>) -> Unit) {
    var lastId: String? = null
    while (true) {
        val query = if (lastId == null) {
            "SELECT id,createdAt,entities FROM bookmarks ORDER BY id LIMIT 256"
        } else {
            "SELECT id,createdAt,entities FROM bookmarks WHERE id > ? ORDER BY id LIMIT 256"
        }
        val rows = db.query(query, lastId?.let { arrayOf<Any>(it) } ?: emptyArray()).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(LegacyEntityRow(cursor.getString(0), cursor.getLong(1), cursor.getString(2)))
            }
        }
        if (rows.isEmpty()) return
        consume(rows)
        lastId = rows.last().id
    }
}
