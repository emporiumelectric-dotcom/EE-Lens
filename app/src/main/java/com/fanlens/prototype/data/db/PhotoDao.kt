package com.fanlens.prototype.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.fanlens.prototype.data.db.entity.PhotoEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PhotoDao {

    @Query("SELECT * FROM photos WHERE product_id = :productId ORDER BY sort_order")
    suspend fun forProduct(productId: String): List<PhotoEntity>

    @Query("SELECT * FROM photos WHERE product_id = :productId ORDER BY sort_order")
    fun observeForProduct(productId: String): Flow<List<PhotoEntity>>

    @Query("SELECT * FROM photos WHERE id = :id")
    suspend fun byId(id: String): PhotoEntity?

    @Query("SELECT COUNT(*) FROM photos WHERE product_id = :productId AND sha256 = :sha256")
    suspend fun duplicateCount(productId: String, sha256: String): Int

    @Query("SELECT COALESCE(MAX(sort_order), -1) + 1 FROM photos WHERE product_id = :productId")
    suspend fun nextSortOrder(productId: String): Int

    /**
     * Photos belonging to live products that have no fingerprint for the current
     * model — the backfill queue.
     */
    /**
     * Every photo is fingerprinted, whatever its role. Shop photos and catalogue
     * photos feed two separate indexes with their own thresholds, so a catalogue
     * image can be recognised on a screen without touching shelf accuracy.
     */
    @Query(
        """
        SELECT p.* FROM photos p
        INNER JOIN products pr ON pr.id = p.product_id
        LEFT JOIN embeddings e ON e.photo_id = p.id AND e.model_version = :modelVersion
        WHERE pr.deleted_at IS NULL AND e.photo_id IS NULL
        ORDER BY p.product_id, p.sort_order
        """
    )
    suspend fun missingFingerprints(modelVersion: String): List<PhotoEntity>

    @Query("UPDATE photos SET role = :role WHERE id = :photoId")
    suspend fun setRole(photoId: String, role: String)

    @Query("UPDATE photos SET synced_at = :syncedAt WHERE id = :photoId")
    suspend fun setSyncedAt(photoId: String, syncedAt: Long)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(photo: PhotoEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(photos: List<PhotoEntity>)

    @Query("DELETE FROM photos WHERE id = :id")
    suspend fun delete(id: String)
}
