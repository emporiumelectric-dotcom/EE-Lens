package com.fanlens.prototype.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.fanlens.prototype.data.db.entity.ProductEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProductDao {

    @Query("SELECT * FROM products WHERE deleted_at IS NULL ORDER BY brand COLLATE NOCASE, name COLLATE NOCASE")
    fun observeLive(): Flow<List<ProductEntity>>

    @Query("SELECT * FROM products WHERE deleted_at IS NULL ORDER BY brand COLLATE NOCASE, name COLLATE NOCASE")
    suspend fun liveProducts(): List<ProductEntity>

    @Query("SELECT * FROM products WHERE id = :id")
    suspend fun byId(id: String): ProductEntity?

    /** For a legacy-id product whose local id is not the cloud's client_id -- see ProductEntity.cloudClientId. */
    @Query("SELECT * FROM products WHERE cloud_client_id = :cloudClientId")
    suspend fun byCloudClientId(cloudClientId: String): ProductEntity?

    @Query("UPDATE products SET cloud_client_id = :cloudClientId WHERE id = :id")
    suspend fun setCloudClientId(id: String, cloudClientId: String)

    /** Includes soft-deleted rows: a product the owner deleted must not be re-seeded. */
    @Query("SELECT id FROM products")
    suspend fun allIdsIncludingDeleted(): List<String>

    /** Includes soft-deleted rows, so a cloud push also carries their deleted_at forward. */
    @Query("SELECT * FROM products")
    suspend fun allIncludingDeleted(): List<ProductEntity>

    @Query("SELECT COUNT(*) FROM products WHERE deleted_at IS NULL")
    suspend fun liveCount(): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(product: ProductEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(products: List<ProductEntity>)

    @Update
    suspend fun update(product: ProductEntity)

    @Query("UPDATE products SET deleted_at = :deletedAt, updated_at = :deletedAt WHERE id = :id")
    suspend fun softDelete(id: String, deletedAt: Long)

    @Query("UPDATE products SET deleted_at = NULL, updated_at = :updatedAt WHERE id = :id")
    suspend fun restore(id: String, updatedAt: Long)

    /** Rows soft-deleted before [before]; their files are removed as part of the purge. */
    @Query("SELECT * FROM products WHERE deleted_at IS NOT NULL AND deleted_at < :before")
    suspend fun purgeable(before: Long): List<ProductEntity>

    @Query("DELETE FROM products WHERE id = :id")
    suspend fun deleteForever(id: String)
}
