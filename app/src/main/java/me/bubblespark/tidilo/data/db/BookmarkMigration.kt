package me.bubblespark.tidilo.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.bubblespark.tidilo.data.DomainIconManager
import me.bubblespark.tidilo.data.entitySkeletonKey

/** Completes all derived-data backfill before Room exposes the upgraded database. */
internal val bookmarkMigration6To7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE bookmarks ADD COLUMN domain TEXT")
        for (name in listOf("searchTitle", "searchDescription", "searchUrl")) {
            db.execSQL("ALTER TABLE bookmarks ADD COLUMN $name TEXT NOT NULL DEFAULT ''")
        }
        db.execSQL("ALTER TABLE bookmarks ADD COLUMN searchCategory TEXT")
        db.execSQL("ALTER TABLE bookmarks ADD COLUMN searchSubcategory TEXT")
        db.execSQL("CREATE INDEX index_bookmarks_domain ON bookmarks(domain)")
        db.execSQL("""CREATE TABLE bookmark_entities (
            bookmarkId TEXT NOT NULL, position INTEGER NOT NULL, value TEXT NOT NULL,
            searchValue TEXT NOT NULL, PRIMARY KEY(bookmarkId, position),
            FOREIGN KEY(bookmarkId) REFERENCES bookmarks(id) ON UPDATE NO ACTION ON DELETE CASCADE)""")
        db.execSQL("CREATE INDEX index_bookmark_entities_bookmarkId ON bookmark_entities(bookmarkId)")
        rebuildBookmarkDerivedData(db)
    }
}

// Reserved for derived-data format only; Room's user_version and schema identity remain unchanged.
internal val BOOKMARK_DERIVED_FORMAT = if (supplementaryIgnoreCase) 0x54494437 else 0x54494438

internal fun rebuildBookmarkDerivedData(db: SupportSQLiteDatabase) {
    db.execSQL("DELETE FROM bookmark_entities")
    val update = db.compileStatement("""UPDATE bookmarks SET domain=?, searchTitle=?,
        searchDescription=?, searchUrl=?, searchCategory=?, searchSubcategory=? WHERE id=?""")
    val hasSkeletonKey = db.query("PRAGMA table_info(bookmark_entities)").use { cursor ->
        var found = false
        while (cursor.moveToNext()) if (cursor.getString(1) == "skeletonKey") found = true
        found
    }
    val occurrence = db.compileStatement(if (hasSkeletonKey) {
        "INSERT INTO bookmark_entities (bookmarkId,position,value,searchValue,skeletonKey) VALUES (?, ?, ?, ?, ?)"
    } else {
        "INSERT INTO bookmark_entities (bookmarkId,position,value,searchValue) VALUES (?, ?, ?, ?)"
    })
    try {
        var lastId: String? = null
        while (true) {
            // Only the required scalar fields of at most 256 rows are read at a time.
            val rows = mutableListOf<Array<String?>>()
            val query = if (lastId == null) {
                "SELECT id,url,title,description,category,subcategory,entities,isAiSkipped FROM bookmarks ORDER BY id LIMIT 256"
            } else {
                "SELECT id,url,title,description,category,subcategory,entities,isAiSkipped FROM bookmarks WHERE id > ? ORDER BY id LIMIT 256"
            }
            db.query(query, lastId?.let { arrayOf<Any>(it) } ?: emptyArray()).use { cursor ->
                while (cursor.moveToNext()) {
                    rows.add(Array(8) { if (cursor.isNull(it)) null else cursor.getString(it) })
                }
            }
            if (rows.isEmpty()) break
            for (row in rows) {
                val id = requireNotNull(row[0])
                val tag = logicalBookmarkTag(row[4], row[5], Converters().toStringList(row[6]), row[7] == "1")
                val values = listOf(DomainIconManager.extractDomain(row[1].orEmpty()),
                    bookmarkSearchKey(row[2].orEmpty()), bookmarkSearchKey(row[3].orEmpty()),
                    bookmarkSearchKey(row[1].orEmpty()), tag.category?.let(::bookmarkSearchKey),
                    tag.subcategory?.let(::bookmarkSearchKey), id)
                update.clearBindings()
                values.forEachIndexed { index, value ->
                    if (value == null) update.bindNull(index + 1) else update.bindString(index + 1, value)
                }
                update.executeUpdateDelete()
                tag.entities.forEachIndexed { index, value ->
                    occurrence.clearBindings()
                    occurrence.bindString(1, id)
                    occurrence.bindLong(2, index.toLong())
                    occurrence.bindString(3, value)
                    occurrence.bindString(4, bookmarkSearchKey(value))
                    if (hasSkeletonKey) occurrence.bindString(5, entitySkeletonKey(value))
                    occurrence.executeInsert()
                }
                lastId = id
            }
        }
    } finally {
        update.close()
        occurrence.close()
    }
    db.execSQL("PRAGMA application_id=$BOOKMARK_DERIVED_FORMAT")
}
