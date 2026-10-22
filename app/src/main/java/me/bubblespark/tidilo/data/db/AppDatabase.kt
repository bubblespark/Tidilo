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
        ImportTaskEntity::class
    ],
    version = 7,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun bookmarkDao(): BookmarkDao
    abstract fun trashDao(): TrashDao
    abstract fun importTaskDao(): ImportTaskDao

    companion object {
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
                    .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                    .addCallback(DERIVED_DATA_CALLBACK)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
