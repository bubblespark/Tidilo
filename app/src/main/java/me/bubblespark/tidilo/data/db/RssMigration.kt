package me.bubblespark.tidilo.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds nullable provenance to existing rows without changing their manual ownership. */
internal val rssMigration7To8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        for (table in listOf("bookmarks", "trashed_bookmarks", "import_tasks")) {
            db.execSQL("ALTER TABLE `$table` ADD COLUMN `rssFeedId` TEXT")
            db.execSQL("ALTER TABLE `$table` ADD COLUMN `rssPublishedAt` INTEGER")
            db.execSQL("ALTER TABLE `$table` ADD COLUMN `ogImageKey` TEXT")
            db.query("SELECT id,url,ogImageUrl FROM `$table` WHERE ogImageUrl IS NOT NULL").use { rows ->
                while (rows.moveToNext()) {
                    val key = bookmarkOgImageKey(rows.getString(1), rows.getString(2))
                    db.execSQL("UPDATE `$table` SET ogImageKey=? WHERE id=?", arrayOf(key, rows.getString(0)))
                }
            }
            db.execSQL("CREATE INDEX IF NOT EXISTS index_${table}_ogImageKey ON `$table`(ogImageKey)")
        }
        db.execSQL("CREATE TABLE IF NOT EXISTS rss_feeds (id TEXT NOT NULL PRIMARY KEY, url TEXT NOT NULL, title TEXT NOT NULL, mode TEXT NOT NULL, retentionLimit INTEGER, deleted INTEGER NOT NULL, revision INTEGER NOT NULL, etag TEXT, lastModified TEXT, lastAttemptAt INTEGER, lastSuccessAt INTEGER, error TEXT)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_rss_feeds_url ON rss_feeds(url)")
        db.execSQL("CREATE TABLE IF NOT EXISTS rss_seen_items (feedId TEXT NOT NULL, itemKey TEXT NOT NULL, url TEXT NOT NULL, PRIMARY KEY(feedId, itemKey))")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_rss_seen_items_url ON rss_seen_items(url)")
        db.execSQL("CREATE TABLE IF NOT EXISTS rss_image_cleanup (imageUrl TEXT NOT NULL PRIMARY KEY, articleUrl TEXT NOT NULL)")
        for (table in listOf("bookmarks", "import_tasks")) {
            db.execSQL("CREATE INDEX IF NOT EXISTS index_${table}_rssFeedId ON `$table`(rssFeedId)")
        }
    }
}

/** Canonical image identity matches the shared cache, including relative URLs and fragments. */
internal fun bookmarkOgImageKey(url: String, image: String?): String? = image?.takeIf { it.isNotBlank() }?.let {
    me.bubblespark.tidilo.data.OgImageManager.computeImageKey(me.bubblespark.tidilo.data.OgImageManager.resolveImageUrl(url, it))
}
