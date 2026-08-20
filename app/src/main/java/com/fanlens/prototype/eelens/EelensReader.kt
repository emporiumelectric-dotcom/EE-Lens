package com.fanlens.prototype.eelens

import com.fanlens.prototype.model.PhotoOrigin
import com.fanlens.prototype.model.PhotoRole
import com.fanlens.prototype.model.Product
import com.fanlens.prototype.model.ProductSource
import com.fanlens.prototype.util.Hashing
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/** One photo inside a package, already checked against its checksum. */
data class StagedPhoto(
    val id: String,
    val productId: String,
    val bytes: ByteArray,
    val sha256: String,
    val width: Int,
    val height: Int,
    val sortOrder: Int,
    val origin: PhotoOrigin,
    val role: PhotoRole
) {
    override fun equals(other: Any?): Boolean = other is StagedPhoto && id == other.id
    override fun hashCode(): Int = id.hashCode()
}

data class StagedProduct(
    val product: Product,
    val photos: List<StagedPhoto>
)

/** A package that has been read and fully checked, but not yet committed. */
data class StagedPackage(
    val preview: ImportPreview,
    val products: List<StagedProduct>
)

/**
 * Reads and validates a package before any of it is used.
 *
 * Every refusal carries a message meant for the shop owner. Nothing here writes
 * to the catalogue — that only happens once the owner has seen the preview.
 */
class EelensReader {

    /**
     * @param file a local copy of the chosen package; a content Uri is copied to
     *   cache first so the archive can be read with random access.
     * @param knownProductIds ids already stored, to report clashes up front.
     */
    fun read(file: File, knownProductIds: Set<String>): StagedPackage {
        if (!file.isFile || file.length() == 0L) {
            throw EelensException("That file is empty.")
        }

        val zip = try {
            ZipFile(file)
        } catch (_: Throwable) {
            throw EelensException("This file isn't an EE Lens catalogue.")
        }

        zip.use { archive ->
            val manifestBytes = archive.bytesOf(EelensFormat.MANIFEST)
                ?: throw EelensException("This file isn't an EE Lens catalogue.")

            val manifest = try {
                JSONObject(String(manifestBytes, Charsets.UTF_8))
            } catch (_: JSONException) {
                throw EelensException("This catalogue file is damaged and cannot be read.")
            }

            if (manifest.optString("format") != EelensFormat.FORMAT) {
                throw EelensException("This file isn't an EE Lens catalogue.")
            }
            val version = manifest.optInt("formatVersion", -1)
            if (version <= 0) throw EelensException("This catalogue file is damaged.")
            if (version > EelensFormat.FORMAT_VERSION) {
                throw EelensException(
                    "This catalogue was made by a newer version of EE Lens. Update the app, then try again."
                )
            }

            val productsBytes = archive.bytesOf(EelensFormat.PRODUCTS)
                ?: throw EelensException("This catalogue is missing its product list.")

            val integrity = manifest.optJSONObject("integrity")
            val declared = integrity?.optString("productsSha256").orEmpty()
            if (declared.isNotEmpty() && Hashing.sha256(productsBytes) != declared) {
                throw EelensException(
                    "This catalogue is damaged: the product list does not match its checksum."
                )
            }

            val parsed = try {
                JSONObject(String(productsBytes, Charsets.UTF_8))
            } catch (_: JSONException) {
                throw EelensException("This catalogue is damaged: the product list could not be read.")
            }

            val photoHashes = integrity?.optJSONObject("photoHashes")
            val list = parsed.optJSONArray("products")
                ?: throw EelensException("This catalogue contains no products.")

            val staged = mutableListOf<StagedProduct>()
            val missing = mutableListOf<String>()
            val corrupt = mutableListOf<String>()
            var photoCount = 0

            for (i in 0 until list.length()) {
                val node = list.optJSONObject(i) ?: continue
                val product = node.toProduct() ?: continue
                val photosJson = node.optJSONArray("photos")
                val photos = mutableListOf<StagedPhoto>()

                for (j in 0 until (photosJson?.length() ?: 0)) {
                    val record = photosJson!!.optJSONObject(j) ?: continue
                    val photoId = record.optString("id").ifBlank { continue }
                    val path = EelensFormat.photoPath(product.id, photoId)
                    photoCount++

                    val bytes = archive.bytesOf(path)
                    if (bytes == null) { missing += path; continue }

                    val expected = photoHashes?.optString(path).orEmpty()
                        .ifBlank { record.optString("sha256") }
                    val actual = Hashing.sha256(bytes)
                    if (expected.isNotBlank() && expected != actual) { corrupt += path; continue }

                    photos += StagedPhoto(
                        id = photoId,
                        productId = product.id,
                        bytes = bytes,
                        sha256 = actual,
                        width = record.optInt("width"),
                        height = record.optInt("height"),
                        sortOrder = record.optInt("sortOrder", j),
                        origin = PhotoOrigin.fromStorage(record.optString("origin")),
                        role = PhotoRole.fromStorage(record.optString("role"))
                    )
                }
                staged += StagedProduct(product, photos)
            }

            if (staged.isEmpty()) throw EelensException("This catalogue contains no products.")

            return StagedPackage(
                preview = ImportPreview(
                    formatVersion = version,
                    createdBy = manifest.optJSONObject("createdBy")?.optString("tool").orEmpty()
                        .ifBlank { "an unknown tool" },
                    products = staged.size,
                    photos = photoCount,
                    alreadyHere = staged.count { it.product.id in knownProductIds },
                    missingPhotos = missing,
                    corruptPhotos = corrupt
                ),
                products = staged
            )
        }
    }

    private fun ZipFile.bytesOf(name: String): ByteArray? =
        getEntry(name)?.let { entry -> getInputStream(entry).use { it.readBytes() } }

    private fun JSONObject.toProduct(): Product? {
        val id = optString("id").ifBlank { return null }
        val name = optString("name").ifBlank { return null }
        val specs = buildMap {
            optJSONObject("specs")?.let { json ->
                json.keys().forEach { key -> put(key, json.optString(key)) }
            }
        }
        return Product(
            id = id,
            name = name,
            model = optString("model"),
            description = optString("description"),
            slug = optString("slug"),
            brand = optString("brand"),
            category = optStringOrNull("category"),
            colour = optStringOrNull("colour"),
            sizeSweepMm = if (isNull("sizeSweepMm")) null else optInt("sizeSweepMm"),
            priceMinor = if (isNull("priceMinor")) null else optLong("priceMinor"),
            // Absent in format version 1; simply means no MRP was recorded.
            mrpMinor = if (isNull("mrpMinor")) null else optLong("mrpMinor"),
            currency = optString("currency").ifBlank { "INR" },
            specs = specs,
            coverPhotoId = optStringOrNull("coverPhotoId"),
            source = ProductSource.fromStorage(optString("source")),
            createdAt = optLong("createdAt", System.currentTimeMillis()),
            updatedAt = optLong("updatedAt", System.currentTimeMillis())
        )
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).ifBlank { null }
}
