package me.bubblespark.tidilo.data.db

import androidx.room.Entity
import androidx.room.Index

/**
 * Cached Open Graph protocol image (og:image) stored as a compressed WebP byte array.
 *
 * - Primary Key: Composite key of [domain] and [filename] to prevent duplicate downloads.
 * - Image size is compressed to under 50KB using WebP lossy compression.
 */
@Entity(
    tableName = "og_images",
    primaryKeys = ["domain", "filename"],
    indices = [
        Index(value = ["domain"]),
        Index(value = ["createdAt"])
    ]
)
data class OgImageEntity(
    val domain: String,
    val filename: String,
    val imageBlob: ByteArray,
    val mimeType: String = "image/webp",
    val width: Int,
    val height: Int,
    val sizeBytes: Int,
    val createdAt: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as OgImageEntity

        if (domain != other.domain) return false
        if (filename != other.filename) return false
        if (!imageBlob.contentEquals(other.imageBlob)) return false
        if (mimeType != other.mimeType) return false
        if (width != other.width) return false
        if (height != other.height) return false
        if (sizeBytes != other.sizeBytes) return false
        if (createdAt != other.createdAt) return false

        return true
    }

    override fun hashCode(): Int {
        var result = domain.hashCode()
        result = 31 * result + filename.hashCode()
        result = 31 * result + imageBlob.contentHashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + sizeBytes
        result = 31 * result + createdAt.hashCode()
        return result
    }
}
