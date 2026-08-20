package com.fanlens.prototype.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One fingerprint per photo. The model identity is stored alongside it so that
 * upgrading the recognition model invalidates old rows loudly rather than
 * silently degrading matches.
 */
@Entity(
    tableName = "embeddings",
    foreignKeys = [
        ForeignKey(
            entity = PhotoEntity::class,
            parentColumns = ["id"],
            childColumns = ["photo_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["model_version"])]
)
data class EmbeddingEntity(
    @PrimaryKey
    @ColumnInfo(name = "photo_id")
    val photoId: String,

    @ColumnInfo(name = "model_id")
    val modelId: String,

    /** Hash of the .tflite asset that produced this fingerprint. */
    @ColumnInfo(name = "model_version")
    val modelVersion: String,

    @ColumnInfo(name = "dim")
    val dim: Int,

    /** Little-endian float32, L2-normalised. */
    @ColumnInfo(name = "vector", typeAffinity = ColumnInfo.BLOB)
    val vector: ByteArray,

    @ColumnInfo(name = "created_at")
    val createdAt: Long
) {
    override fun equals(other: Any?): Boolean =
        other is EmbeddingEntity &&
            photoId == other.photoId &&
            modelId == other.modelId &&
            modelVersion == other.modelVersion &&
            dim == other.dim &&
            createdAt == other.createdAt &&
            vector.contentEquals(other.vector)

    override fun hashCode(): Int {
        var result = photoId.hashCode()
        result = 31 * result + modelId.hashCode()
        result = 31 * result + modelVersion.hashCode()
        result = 31 * result + dim
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + vector.contentHashCode()
        return result
    }
}
