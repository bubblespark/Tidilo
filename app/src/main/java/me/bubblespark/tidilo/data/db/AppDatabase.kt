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
        TrashedBookmarkEntity::class,
        ImportTaskEntity::class,
        OgImageEntity::class
    ],
    version = 4,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun bookmarkDao(): BookmarkDao
    abstract fun trashDao(): TrashDao
    abstract fun importTaskDao(): ImportTaskDao
    abstract fun ogImageDao(): OgImageDao

    companion object {
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
                    .addMigrations(MIGRATION_3_4)
                    .fallbackToDestructiveMigration(true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
