package me.bubblespark.tidilo.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import me.bubblespark.tidilo.data.DomainPolicy

/**
 * Room entity storing per-domain NSFW policy and display name.
 */
@Entity(tableName = "domain_policies")
data class DomainPolicyEntity(
    @PrimaryKey
    val domain: String,
    val isNsfw: Boolean = false,
    val displayName: String = ""
) {
    fun toDomainPolicy(): DomainPolicy = DomainPolicy(
        domain = domain,
        isNsfw = isNsfw,
        displayName = displayName
    )

    companion object {
        fun fromDomainPolicy(policy: DomainPolicy): DomainPolicyEntity = DomainPolicyEntity(
            domain = policy.domain,
            isNsfw = policy.isNsfw,
            displayName = policy.displayName
        )
    }
}
