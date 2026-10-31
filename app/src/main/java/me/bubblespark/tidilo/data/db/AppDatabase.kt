package me.bubblespark.tidilo.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Main Room database for Tidilo.
 */
@Database(
    entities = [
        BookmarkEntity::class,
        BookmarkEntityOccurrence::class,
        TrashedBookmarkEntity::class,
        RssFeedEntity::class,
        RssSeenItemEntity::class,
        RssImageCleanupEntity::class,
        ImportTaskEntity::class,
        ImportNotificationEntity::class,
        RssNotificationRunEntity::class,
        RssNotificationItemEntity::class
    ],
    version = 14,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun rssDao(): RssDao

    abstract fun bookmarkDao(): BookmarkDao
    abstract fun trashDao(): TrashDao
    abstract fun importTaskDao(): ImportTaskDao
    abstract fun importNotificationDao(): ImportNotificationDao
    abstract fun rssNotificationDao(): RssNotificationDao

    companion object {
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (table in listOf("bookmarks", "trashed_bookmarks")) {
                    db.execSQL("ALTER TABLE $table ADD COLUMN isLiked INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE $table ADD COLUMN browserOpenCount INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE $table ADD COLUMN detailViewCount INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE $table ADD COLUMN summaryPeekCount INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE $table ADD COLUMN lastInteractedAt INTEGER")
                }
            }
        }
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS rss_notification_runs (id TEXT NOT NULL PRIMARY KEY, workId TEXT NOT NULL, requestedAt INTEGER NOT NULL, showSummary INTEGER NOT NULL, completed INTEGER NOT NULL, published INTEGER NOT NULL, pendingAi INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS rss_notification_items (runId TEXT NOT NULL, itemId TEXT NOT NULL, outcome TEXT NOT NULL, detail TEXT NOT NULL, PRIMARY KEY(runId, itemId))")
            }
        }
        /** Repairs an already-installed prerelease v7 once, before Room publishes any queries. */
        internal val DERIVED_DATA_CALLBACK = object : Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                val current = db.query("PRAGMA application_id").use { it.moveToFirst(); it.getInt(0) }
                if (current == BOOKMARK_DERIVED_FORMAT) return
                db.beginTransaction()
                try {
                    rebuildBookmarkDerivedData(db)
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
        }

        /** Preserves v1 rows while allowing nullable tags and adding the AI-skipped flag. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS `bookmarks_v2` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `url` TEXT NOT NULL, `description` TEXT NOT NULL, `category` TEXT, `subcategory` TEXT, `entities` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `note` TEXT NOT NULL, `isNsfw` INTEGER NOT NULL, `isAiSkipped` INTEGER NOT NULL, PRIMARY KEY(`id`))""")
                db.execSQL("INSERT INTO bookmarks_v2 (id,title,url,description,category,subcategory,entities,createdAt,updatedAt,note,isNsfw,isAiSkipped) SELECT id,title,url,description,category,subcategory,entities,createdAt,updatedAt,note,isNsfw,0 FROM bookmarks")
                db.execSQL("DROP TABLE bookmarks")
                db.execSQL("ALTER TABLE bookmarks_v2 RENAME TO bookmarks")
                db.execSQL("""CREATE INDEX IF NOT EXISTS `index_bookmarks_url` ON `bookmarks` (`url`)""")
                db.execSQL("""CREATE INDEX IF NOT EXISTS `index_bookmarks_createdAt` ON `bookmarks` (`createdAt`)""")
                db.execSQL("""CREATE INDEX IF NOT EXISTS `index_bookmarks_category` ON `bookmarks` (`category`)""")
                db.execSQL("""CREATE INDEX IF NOT EXISTS `index_bookmarks_subcategory` ON `bookmarks` (`subcategory`)""")
                db.execSQL("""CREATE INDEX IF NOT EXISTS `index_bookmarks_isNsfw` ON `bookmarks` (`isNsfw`)""")
                db.execSQL("""CREATE INDEX IF NOT EXISTS `index_bookmarks_isAiSkipped` ON `bookmarks` (`isAiSkipped`)""")
                db.execSQL("""CREATE TABLE IF NOT EXISTS `trashed_bookmarks_v2` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `url` TEXT NOT NULL, `description` TEXT NOT NULL, `category` TEXT, `subcategory` TEXT, `entities` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `note` TEXT NOT NULL, `isNsfw` INTEGER NOT NULL, `isAiSkipped` INTEGER NOT NULL, `trashedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))""")
                db.execSQL("INSERT INTO trashed_bookmarks_v2 (id,title,url,description,category,subcategory,entities,createdAt,updatedAt,note,isNsfw,isAiSkipped,trashedAt) SELECT id,title,url,description,category,subcategory,entities,createdAt,updatedAt,note,isNsfw,0,trashedAt FROM trashed_bookmarks")
                db.execSQL("DROP TABLE trashed_bookmarks")
                db.execSQL("ALTER TABLE trashed_bookmarks_v2 RENAME TO trashed_bookmarks")
                db.execSQL("""CREATE INDEX IF NOT EXISTS `index_trashed_bookmarks_trashedAt` ON `trashed_bookmarks` (`trashedAt`)""")
            }
        }

        /** Adds the import queue without replacing bookmarks or trash. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS `import_tasks` (`id` TEXT NOT NULL, `url` TEXT NOT NULL, `title` TEXT NOT NULL, `status` TEXT NOT NULL, `statusText` TEXT NOT NULL, `errorMessage` TEXT, `assignedTags` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `isNsfw` INTEGER NOT NULL, `siteName` TEXT NOT NULL, `description` TEXT NOT NULL, `bodySnippet` TEXT NOT NULL, PRIMARY KEY(`id`))""")
                db.execSQL("""CREATE INDEX IF NOT EXISTS `index_import_tasks_url` ON `import_tasks` (`url`)""")
                db.execSQL("""CREATE INDEX IF NOT EXISTS `index_import_tasks_status` ON `import_tasks` (`status`)""")
                db.execSQL("""CREATE INDEX IF NOT EXISTS `index_import_tasks_createdAt` ON `import_tasks` (`createdAt`)""")
            }
        }

        val MIGRATION_7_8 = rssMigration7To8
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `import_notifications` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`type` TEXT NOT NULL, " +
                        "`domain` TEXT NOT NULL, " +
                        "`url` TEXT NOT NULL, " +
                        "`message` TEXT NOT NULL, " +
                        "`existingBookmarkId` TEXT, " +
                        "`isRead` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_import_notifications_createdAt` ON `import_notifications` (`createdAt`)")
            }
        }
        /** Preserves both the notification v11 schema and the earlier RSS-only v11 build. */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_10_11.migrate(db)
                val columns = db.query("PRAGMA table_info(rss_feeds)").use { cursor ->
                    buildSet { while (cursor.moveToNext()) add(cursor.getString(1)) }
                }
                if ("iconUrl" !in columns) db.execSQL("ALTER TABLE rss_feeds ADD COLUMN iconUrl TEXT")
                if ("siteUrl" !in columns) db.execSQL("ALTER TABLE rss_feeds ADD COLUMN siteUrl TEXT")
            }
        }
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE rss_feeds ADD COLUMN acquisitionId TEXT")
                db.execSQL("ALTER TABLE rss_feeds ADD COLUMN acquisitionMobileOverride INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE rss_feeds ADD COLUMN acquisitionXmlDone INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE rss_feeds ADD COLUMN acquisitionRequestedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE import_tasks ADD COLUMN articlePrepared INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE import_tasks ADD COLUMN intendedMode TEXT NOT NULL DEFAULT 'AI'")
                db.execSQL("ALTER TABLE import_tasks ADD COLUMN rssItemKey TEXT")
                db.execSQL("ALTER TABLE import_tasks ADD COLUMN acquisitionAttempts INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE import_tasks ADD COLUMN acquisitionRetryAt INTEGER NOT NULL DEFAULT 0")
                // Every legacy RSS snippet came from XML, even when nonblank.
                db.execSQL("UPDATE import_tasks SET articlePrepared = 1 WHERE rssFeedId IS NULL AND bodySnippet <> ''")
                db.execSQL("UPDATE import_tasks SET acquisitionRetryAt = 9223372036854775807 WHERE rssFeedId IS NOT NULL AND status = 'ERROR'")
            }
        }
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE rss_feeds ADD COLUMN enabled INTEGER NOT NULL DEFAULT 1")
            }
        }

        val MIGRATION_6_7 = bookmarkMigration6To7

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `import_tasks` ADD COLUMN `ogImageUrl` TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `og_images` (" +
                        "`domain` TEXT NOT NULL, `filename` TEXT NOT NULL, `imageBlob` BLOB NOT NULL, " +
                        "`mimeType` TEXT NOT NULL, `width` INTEGER NOT NULL, `height` INTEGER NOT NULL, " +
                        "`sizeBytes` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`domain`, `filename`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_og_images_domain` ON `og_images` (`domain`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_og_images_createdAt` ON `og_images` (`createdAt`)")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `bookmarks` ADD COLUMN `ogImageUrl` TEXT")
                db.execSQL("ALTER TABLE `trashed_bookmarks` ADD COLUMN `ogImageUrl` TEXT")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `og_images`")
            }
        }

        private const val DATABASE_NAME = "tidilo_database.db"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14)
                    .addCallback(DERIVED_DATA_CALLBACK)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
