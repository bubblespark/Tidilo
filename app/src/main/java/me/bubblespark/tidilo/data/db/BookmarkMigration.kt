package me.bubblespark.tidilo.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.bubblespark.tidilo.data.DomainIconManager

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
        val update = db.compileStatement("""UPDATE bookmarks SET domain=?, searchTitle=?,
            searchDescription=?, searchUrl=?, searchCategory=?, searchSubcategory=? WHERE id=?""")
        val occurrence = db.compileStatement("INSERT INTO bookmark_entities VALUES (?, ?, ?, ?)")
        try {
            var lastId: String? = null
            while (true) {
                // Only the required scalar fields of at most 256 rows are read at a time.
                val rows = mutableListOf<Array<String?>>()
                val query = if (lastId == null) {
                    "SELECT id,url,title,description,category,subcategory,entities FROM bookmarks ORDER BY id LIMIT 256"
                } else {
                    "SELECT id,url,title,description,category,subcategory,entities FROM bookmarks WHERE id > ? ORDER BY id LIMIT 256"
                }
                db.query(query, lastId?.let { arrayOf<Any>(it) } ?: emptyArray()).use { cursor ->
                    while (cursor.moveToNext()) {
                        rows.add(Array(7) { if (cursor.isNull(it)) null else cursor.getString(it) })
                    }
                }
                if (rows.isEmpty()) break
                for (row in rows) {
                    val id = requireNotNull(row[0])
                    val values = listOf(DomainIconManager.extractDomain(row[1].orEmpty()),
                        bookmarkSearchKey(row[2].orEmpty()), bookmarkSearchKey(row[3].orEmpty()),
                        bookmarkSearchKey(row[1].orEmpty()), row[4]?.let(::bookmarkSearchKey),
                        row[5]?.let(::bookmarkSearchKey), id)
                    update.clearBindings()
                    values.forEachIndexed { index, value ->
                        if (value == null) update.bindNull(index + 1) else update.bindString(index + 1, value)
                    }
                    update.executeUpdateDelete()
                    Converters().toStringList(row[6]).forEachIndexed { index, value ->
                        occurrence.clearBindings()
                        occurrence.bindString(1, id)
                        occurrence.bindLong(2, index.toLong())
                        occurrence.bindString(3, value)
                        occurrence.bindString(4, bookmarkSearchKey(value))
                        occurrence.executeInsert()
                    }
                    lastId = id
                }
            }
        } finally {
            update.close()
            occurrence.close()
        }
    }
}
