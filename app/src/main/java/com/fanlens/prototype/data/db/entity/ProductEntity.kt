package com.fanlens.prototype.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "products",
    indices = [
        Index(value = ["deleted_at"]),
        Index(value = ["slug"])
    ]
)
data class ProductEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    /** Readable but not identifying — two products may share a slug, never an id. */
    @ColumnInfo(name = "slug")
    val slug: String,

    @ColumnInfo(name = "brand")
    val brand: String,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "model")
    val model: String,

    @ColumnInfo(name = "category")
    val category: String?,

    @ColumnInfo(name = "colour")
    val colour: String?,

    @ColumnInfo(name = "size_sweep_mm")
    val sizeSweepMm: Int?,

    /** What the shop charges, in paise for INR. Never a floating-point value. */
    @ColumnInfo(name = "price_minor")
    val priceMinor: Long?,

    /** List price the selling price is discounted from; null when there is none. */
    @ColumnInfo(name = "mrp_minor")
    val mrpMinor: Long?,

    @ColumnInfo(name = "currency")
    val currency: String,

    @ColumnInfo(name = "description")
    val description: String,

    /** Free-form specifications as a JSON object of string keys and values. */
    @ColumnInfo(name = "specs_json")
    val specsJson: String,

    @ColumnInfo(name = "cover_photo_id")
    val coverPhotoId: String?,

    @ColumnInfo(name = "source")
    val source: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,

    /** Soft delete. NULL means live; a timestamp means hidden and pending purge. */
    @ColumnInfo(name = "deleted_at")
    val deletedAt: Long?,

    /**
     * A generated UUID to use as this product's Supabase client_id, for
     * products whose [id] is not itself a UUID -- the bundled demo catalogue
     * uses fixed slugs like "havells-enticer-vineer", which the uuid-typed
     * client_id column rejects. Null until the first cloud push needs one;
     * once set it never changes, so push/pull always agree on the same
     * cloud identity for this product.
     */
    @ColumnInfo(name = "cloud_client_id")
    val cloudClientId: String? = null
)
