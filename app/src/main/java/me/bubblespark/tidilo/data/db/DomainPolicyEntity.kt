package me.bubblespark.tidilo.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import me.bubblespark.tidilo.data.DomainPolicy

/**
 * Room entity storing per-domain Private Content policy and display name.
 */
@Entity(tableName = "domain_policies")
data class DomainPolicyEntity(
    @PrimaryKey
    val domain: String,
    val isPrivate: Boolean = false,
    val displayName: String = ""
) {
    fun toDomainPolicy(): DomainPolicy = DomainPolicy(
        domain = domain,
        isPrivate = isPrivate,
        displayName = displayName
    )

    companion object {
        fun fromDomainPolicy(policy: DomainPolicy): DomainPolicyEntity = DomainPolicyEntity(
            domain = policy.domain,
            isPrivate = policy.isPrivate,
            displayName = policy.displayName
        )
    }
}
