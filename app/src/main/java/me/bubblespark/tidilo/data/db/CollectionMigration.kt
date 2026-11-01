package me.bubblespark.tidilo.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds manual curation without rebuilding any existing table. */
internal val collectionMigration15To16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS collections (id TEXT NOT NULL PRIMARY KEY,
            title TEXT NOT NULL, description TEXT NOT NULL, presetIconId TEXT, customSymbol TEXT,
            colorIndex INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS bookmark_collection_cross_ref (
            bookmarkId TEXT NOT NULL, collectionId TEXT NOT NULL, PRIMARY KEY(bookmarkId, collectionId),
            FOREIGN KEY(bookmarkId) REFERENCES bookmarks(id) ON UPDATE NO ACTION ON DELETE CASCADE,
            FOREIGN KEY(collectionId) REFERENCES collections(id) ON UPDATE NO ACTION ON DELETE CASCADE)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_bookmark_collection_cross_ref_bookmarkId ON bookmark_collection_cross_ref(bookmarkId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_bookmark_collection_cross_ref_collectionId ON bookmark_collection_cross_ref(collectionId)")
    }
}
