package me.bubblespark.tidilo.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Data Access Object for cached OG images.
 */
@Dao
interface OgImageDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(entity: OgImageEntity): Long

    @Query("SELECT * FROM og_images WHERE domain = :domain AND filename = :filename LIMIT 1")
    suspend fun getOgImage(domain: String, filename: String): OgImageEntity?

    @Query("SELECT COUNT(*) > 0 FROM og_images WHERE domain = :domain AND filename = :filename")
    suspend fun exists(domain: String, filename: String): Boolean

    @Query("SELECT COUNT(*) FROM og_images")
    suspend fun count(): Int

    @Query("DELETE FROM og_images WHERE domain = :domain AND filename = :filename")
    suspend fun delete(domain: String, filename: String): Int

    @Query("DELETE FROM og_images")
    suspend fun deleteAll(): Int
}
