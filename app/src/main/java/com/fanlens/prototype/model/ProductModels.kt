package com.fanlens.prototype.model

import com.fanlens.prototype.util.Money

data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    init {
        require(left in 0f..1f && top in 0f..1f)
        require(right in 0f..1f && bottom in 0f..1f)
        require(left < right && top < bottom)
    }
}

/** Where a product came from. Bundled products are seeded once on first launch. */
enum class ProductSource {
    Bundled, User, Imported;

    fun storageValue(): String = name.lowercase()

    companion object {
        fun fromStorage(value: String?): ProductSource =
            entries.firstOrNull { it.storageValue() == value } ?: User
    }
}

/**
 * What a photo is for.
 *
 * Shop photos taken on the phone are what recognition should learn from; clean
 * catalogue or manufacturer images are what the owner wants customers to see.
 * Mixing the two makes the gallery scruffy and the matching worse.
 */
enum class PhotoRole {
    /** Fingerprinted and used to match camera frames. */
    Recognition,

    /** Shown in the catalogue and product gallery only. */
    Display;

    fun storageValue(): String = name.lowercase()

    companion object {
        fun fromStorage(value: String?): PhotoRole =
            entries.firstOrNull { it.storageValue() == value } ?: Recognition

        /**
         * Where a photo most likely belongs, given how it arrived. Downloads and
         * document folders are where catalogue and internet images land; the
         * camera and the phone's own gallery are where shop photos come from.
         */
        fun defaultFor(origin: PhotoOrigin): PhotoRole = when (origin) {
            PhotoOrigin.Camera, PhotoOrigin.Gallery, PhotoOrigin.Bundled -> Recognition
            PhotoOrigin.File, PhotoOrigin.Import -> Display
        }
    }
}

/** How a reference photo reached the catalogue. */
enum class PhotoOrigin {
    Camera, Gallery, File, Bundled, Import;

    fun storageValue(): String = name.lowercase()

    companion object {
        fun fromStorage(value: String?): PhotoOrigin =
            entries.firstOrNull { it.storageValue() == value } ?: File
    }
}

data class Product(
    val id: String,
    val name: String,
    val model: String,
    val description: String,
    val slug: String = "",
    val brand: String = "",
    val category: String? = null,
    val colour: String? = null,
    val sizeSweepMm: Int? = null,
    /**
     * What the shop charges, in the smallest currency unit — paise for INR.
     * Integers avoid rounding drift.
     */
    val priceMinor: Long? = null,

    /** The list price it is discounted from. Null when the shop sells at MRP. */
    val mrpMinor: Long? = null,

    val currency: String = "INR",
    val specs: Map<String, String> = emptyMap(),
    val coverPhotoId: String? = null,
    val source: ProductSource = ProductSource.User,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
) {
    /** Display price, or null when no price has been entered yet. */
    val priceLabel: String? get() = Money.format(priceMinor, currency)

    /** The struck-through price, shown only when it is genuinely higher. */
    val mrpLabel: String? get() =
        if (mrpMinor != null && priceMinor != null && mrpMinor > priceMinor) {
            Money.format(mrpMinor, currency)
        } else {
            null
        }

    /**
     * Always derived from the two prices, never stored, so it cannot drift out
     * of step with them.
     */
    val discountPercent: Int? get() = Money.discountPercent(mrpMinor, priceMinor)
}

data class Photo(
    val id: String,
    val productId: String,
    val fileName: String,
    val sha256: String,
    val width: Int,
    val height: Int,
    val bytes: Long,
    val sortOrder: Int,
    val origin: PhotoOrigin,
    val role: PhotoRole,
    val createdAt: Long
)

data class ProductWithPhotos(
    val product: Product,
    val photos: List<Photo>
) {
    val displayPhotos: List<Photo> get() = photos.filter { it.role == PhotoRole.Display }
    val recognitionPhotos: List<Photo> get() = photos.filter { it.role == PhotoRole.Recognition }

    /**
     * Customers should see a clean photo where one exists, but a product with
     * only shop photos still needs a thumbnail rather than a blank square.
     */
    val galleryPhotos: List<Photo> get() = displayPhotos.ifEmpty { photos }

    /**
     * Always a catalogue photo when the product has one, even if the stored
     * cover points at a shop photo — customers should never be shown a shelf
     * snapshot while a clean image exists. Falls back to shop photos only for
     * products that have no catalogue photos at all.
     */
    val coverPhoto: Photo?
        get() = galleryPhotos.firstOrNull { it.id == product.coverPhotoId }
            ?: galleryPhotos.firstOrNull()
}

/** Which index produced a match. */
enum class MatchSource {
    /** Matched against shop photos — a real product in front of the camera. */
    Shop,

    /** Matched against catalogue photos — typically an image shown on a screen. */
    Catalogue
}

data class ProductDetection(
    val product: Product,
    val bounds: NormalizedRect,
    val confidence: Float,
    val source: MatchSource = MatchSource.Shop
)

data class RecognitionResult(
    val detection: ProductDetection?,
    val status: String
)
