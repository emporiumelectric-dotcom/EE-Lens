package com.fanlens.prototype.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.fanlens.prototype.data.db.entity.EmbeddingEntity

/** A fingerprint joined to the product it belongs to, ready to load into the index. */
data class FingerprintRow(
    val productId: String,
    val dim: Int,
    val vector: ByteArray
) {
    override fun equals(other: Any?): Boolean =
        other is FingerprintRow &&
            productId == other.productId &&
            dim == other.dim &&
            vector.contentEquals(other.vector)

    override fun hashCode(): Int =
        31 * (31 * productId.hashCode() + dim) + vector.contentHashCode()
}

@Dao
interface EmbeddingDao {

    /**
     * Fingerprints for one role, forming one index. Called twice: once for shop
     * photos and once for catalogue photos.
     */
    @Query(
        """
        SELECT p.product_id AS productId, e.dim AS dim, e.vector AS vector
        FROM embeddings e
        INNER JOIN photos p ON p.id = e.photo_id
        INNER JOIN products pr ON pr.id = p.product_id
        WHERE pr.deleted_at IS NULL AND e.model_version = :modelVersion
          AND p.role = :role
        ORDER BY p.product_id
        """
    )
    suspend fun fingerprintsForRole(modelVersion: String, role: String): List<FingerprintRow>

    @Query("SELECT COUNT(*) FROM embeddings WHERE model_version = :modelVersion")
    suspend fun countForModel(modelVersion: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(embedding: EmbeddingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(embeddings: List<EmbeddingEntity>)

    /** Housekeeping after a model upgrade: rows for older models are dead weight. */
    @Query("DELETE FROM embeddings WHERE model_version != :modelVersion")
    suspend fun deleteOtherModels(modelVersion: String)
}
