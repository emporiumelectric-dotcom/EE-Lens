package com.fanlens.prototype.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "photos",
    foreignKeys = [
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["product_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["product_id"]),
        Index(value = ["sha256"]),
        Index(value = ["role"]),
        // The same image cannot be added to one product twice.
        Index(value = ["product_id", "sha256"], unique = true)
    ]
)
data class PhotoEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "product_id")
    val productId: String,

    /** File name inside the product's private folder. */
    @ColumnInfo(name = "file_name")
    val fileName: String,

    /** Hash of the stored image bytes, used for duplicate detection and backups. */
    @ColumnInfo(name = "sha256")
    val sha256: String,

    @ColumnInfo(name = "width")
    val width: Int,

    @ColumnInfo(name = "height")
    val height: Int,

    @ColumnInfo(name = "bytes")
    val bytes: Long,

    @ColumnInfo(name = "sort_order")
    val sortOrder: Int,

    @ColumnInfo(name = "origin")
    val origin: String,

    /** "recognition" (fingerprinted) or "display" (gallery only). */
    @ColumnInfo(name = "role", defaultValue = "recognition")
    val role: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long
)
