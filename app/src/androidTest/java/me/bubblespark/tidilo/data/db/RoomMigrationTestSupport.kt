package me.bubblespark.tidilo.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import java.security.MessageDigest

internal fun openMigratedDatabase(context: Context, name: String): AppDatabase =
    Room.databaseBuilder(context, AppDatabase::class.java, name)
        .addMigrations(
            AppDatabase.MIGRATION_1_2,
            AppDatabase.MIGRATION_2_3,
            AppDatabase.MIGRATION_3_4,
            AppDatabase.MIGRATION_4_5,
            AppDatabase.MIGRATION_5_6,
            AppDatabase.MIGRATION_6_7,
            AppDatabase.MIGRATION_7_8,
            AppDatabase.MIGRATION_8_9,
            AppDatabase.MIGRATION_9_10,
            AppDatabase.MIGRATION_10_11,
            AppDatabase.MIGRATION_11_12,
            AppDatabase.MIGRATION_12_13,
            AppDatabase.MIGRATION_13_14,
            AppDatabase.MIGRATION_14_15,
            AppDatabase.MIGRATION_15_16,
            AppDatabase.MIGRATION_16_17,
            AppDatabase.MIGRATION_17_18,
            AppDatabase.MIGRATION_18_19,
            AppDatabase.MIGRATION_19_20,
            AppDatabase.MIGRATION_20_21,
            AppDatabase.MIGRATION_21_22,
            AppDatabase.MIGRATION_22_23,
            AppDatabase.MIGRATION_23_24,
        )
        .addCallback(AppDatabase.DERIVED_DATA_CALLBACK)
        .build()

internal fun columns(db: SupportSQLiteDatabase, table: String): List<String> =
    db.query("PRAGMA table_info($table)").use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.getString(1)) }
    }

/** Hashes all original columns with bounded memory, including nulls and column boundaries. */
internal fun fingerprint(db: SupportSQLiteDatabase, table: String, columns: List<String>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    db.query("SELECT ${columns.joinToString { "`$it`" }} FROM $table ORDER BY id").use { cursor ->
        while (cursor.moveToNext()) {
            for (index in columns.indices) {
                val value = if (cursor.isNull(index)) "N;" else {
                    val text = cursor.getString(index)
                    "S${text.length}:$text;"
                }
                digest.update(value.toByteArray(Charsets.UTF_8))
            }
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal fun removeStandardClassificationColumns(execSql: (String) -> Unit) {
    for (column in listOf("standardCategory", "standardSubcategory", "standardClassificationEvaluated")) {
        execSql("ALTER TABLE import_tasks DROP COLUMN $column")
    }
}

internal fun restoreLegacyAiSkippedColumns(execSql: (String) -> Unit) {
    execSql("ALTER TABLE bookmarks ADD COLUMN isAiSkipped INTEGER NOT NULL DEFAULT 0")
    execSql("CREATE INDEX IF NOT EXISTS index_bookmarks_isAiSkipped ON bookmarks(isAiSkipped)")
    execSql("ALTER TABLE trashed_bookmarks ADD COLUMN isAiSkipped INTEGER NOT NULL DEFAULT 0")
}

/** Undoes v24 on a current-schema database: removes the Discover tables. */
internal fun dropDiscoverTables(execSql: (String) -> Unit) {
    for (table in listOf("discover_snapshot", "discover_map", "discover_snapshot_meta", "rediscovery_suppressions", "rediscovery_topic_mutes")) {
        execSql("DROP TABLE IF EXISTS `$table`")
    }
}

/** Undoes v24 and v23 on a current-schema database: restores the engagement counters and removes the event log. */
internal fun restoreEngagementCounters(execSql: (String) -> Unit) {
    dropDiscoverTables(execSql)
    execSql("DROP TABLE IF EXISTS `engagement_events`")
    for (table in listOf("bookmarks", "trashed_bookmarks")) {
        for (column in listOf("browserOpenCount", "detailViewCount", "summaryPeekCount")) {
            execSql("ALTER TABLE `$table` ADD COLUMN `$column` INTEGER NOT NULL DEFAULT 0")
        }
    }
}

/** Rewinds a current-schema database below v22; every such rewind also undoes v23. */
internal fun rewindPrivateColumnsToLegacyNsfw(execSql: (String) -> Unit, includeDomainPolicies: Boolean) {
    restoreEngagementCounters(execSql)
    execSql("DROP INDEX IF EXISTS `index_bookmarks_isPrivate`")
    execSql("ALTER TABLE `bookmarks` RENAME COLUMN `isPrivate` TO `isNsfw`")
    execSql("CREATE INDEX IF NOT EXISTS `index_bookmarks_isNsfw` ON `bookmarks` (`isNsfw`)")
    execSql("ALTER TABLE `trashed_bookmarks` RENAME COLUMN `isPrivate` TO `isNsfw`")
    execSql("ALTER TABLE `import_tasks` RENAME COLUMN `isPrivate` TO `isNsfw`")
    if (includeDomainPolicies) {
        execSql("ALTER TABLE `domain_policies` RENAME COLUMN `isPrivate` TO `isNsfw`")
    } else {
        execSql("DROP TABLE IF EXISTS `domain_policies`")
    }
}
