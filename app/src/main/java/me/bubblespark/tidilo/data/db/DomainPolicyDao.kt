package me.bubblespark.tidilo.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for Room-backed domain policies.
 */
@Dao
interface DomainPolicyDao {
    @Query("SELECT * FROM domain_policies ORDER BY domain ASC")
    fun observeAll(): Flow<List<DomainPolicyEntity>>

    @Query("SELECT * FROM domain_policies ORDER BY domain ASC")
    suspend fun getAll(): List<DomainPolicyEntity>

    @Query("SELECT * FROM domain_policies WHERE domain = :domain")
    suspend fun getByDomain(domain: String): DomainPolicyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(policy: DomainPolicyEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(policies: List<DomainPolicyEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(policy: DomainPolicyEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnore(policies: List<DomainPolicyEntity>)

    @Query("DELETE FROM domain_policies")
    suspend fun deleteAll()
}
