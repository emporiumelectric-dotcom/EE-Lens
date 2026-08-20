package com.fanlens.prototype.eelens

/**
 * The portable catalogue package, shared with the PC Catalogue Manager.
 *
 *   manifest.json           format version, counts, integrity
 *   products.json           product records, each listing its photos
 *   photos/<productId>/<photoId>.jpg
 *
 * Fingerprints are deliberately not carried. They only mean anything for the
 * exact recognition model that produced them, so the phone regenerates them
 * after an import rather than inheriting numbers from an older model.
 */
object EelensFormat {
    const val FORMAT = "eelens"

    /** 1 was the original layout; 2 added mrpMinor alongside priceMinor. */
    const val FORMAT_VERSION = 2

    const val MANIFEST = "manifest.json"
    const val PRODUCTS = "products.json"
    const val PHOTOS_DIR = "photos"

    const val MIME = "application/octet-stream"
    const val EXTENSION = "eelens"

    fun photoPath(productId: String, photoId: String) = "$PHOTOS_DIR/$productId/$photoId.jpg"

    fun suggestFileName(now: Long = System.currentTimeMillis()): String {
        val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.UK).format(java.util.Date(now))
        return "catalogue-$date.$EXTENSION"
    }
}

/** What an export produced. */
data class ExportSummary(
    val products: Int,
    val photos: Int,
    val bytes: Long
)

/** What a package contains, read and checked before anything is committed. */
data class ImportPreview(
    val formatVersion: Int,
    val createdBy: String,
    val products: Int,
    val photos: Int,
    /** Products already in the catalogue under the same id. */
    val alreadyHere: Int,
    /** Files the package promised but did not contain. */
    val missingPhotos: List<String>,
    /** Files whose contents did not match their checksum. */
    val corruptPhotos: List<String>
) {
    val newProducts: Int get() = products - alreadyHere
    val hasProblems: Boolean get() = missingPhotos.isNotEmpty() || corruptPhotos.isNotEmpty()
}

/** How to treat a product that already exists. */
enum class ImportConflictPolicy {
    /** Leave what is here and add only products that are new. */
    KeepMine,

    /** Replace the stored version with the one in the package. */
    ReplaceWithImported
}

data class ImportSummary(
    val productsAdded: Int,
    val productsReplaced: Int,
    val productsSkipped: Int,
    val photosAdded: Int,
    val photosMissing: Int,
    val photosCorrupt: Int
)

/** Refusals that are worth showing the owner verbatim. */
class EelensException(message: String) : Exception(message)
