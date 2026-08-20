package com.fanlens.prototype.eelens

import com.fanlens.prototype.data.PhotoStore
import com.fanlens.prototype.model.Photo
import com.fanlens.prototype.model.Product
import com.fanlens.prototype.util.Hashing
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes a catalogue package.
 *
 * The whole file is built into the stream the owner chose, and a failure part
 * way through leaves an obviously incomplete file rather than a plausible one:
 * the manifest is written first and every photo carries a checksum, so a
 * truncated package fails validation instead of importing silently.
 */
class EelensWriter(private val photoStore: PhotoStore) {

    fun write(
        output: OutputStream,
        products: List<Product>,
        photosByProduct: Map<String, List<Photo>>,
        appVersion: String,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): ExportSummary {
        val photoHashes = JSONObject()
        val productArray = JSONArray()
        var photoCount = 0

        // Product records and photo hashes are built first: the manifest has to
        // state the checksums, so it can only be written once they are known.
        val payloads = mutableListOf<Triple<String, ByteArray, String>>()
        for (product in products) {
            val photos = photosByProduct[product.id].orEmpty().sortedBy(Photo::sortOrder)
            val photoArray = JSONArray()
            for (photo in photos) {
                val file = photoStore.fullFile(photo.productId, photo.id)
                if (!file.isFile) continue
                val bytes = file.readBytes()
                val hash = Hashing.sha256(bytes)
                val path = EelensFormat.photoPath(product.id, photo.id)
                payloads += Triple(path, bytes, hash)
                photoHashes.put(path, hash)
                photoCount++

                photoArray.put(
                    JSONObject().apply {
                        put("id", photo.id)
                        put("fileName", "${photo.id}.jpg")
                        put("sha256", hash)
                        put("width", photo.width)
                        put("height", photo.height)
                        put("bytes", bytes.size)
                        put("sortOrder", photo.sortOrder)
                        put("origin", photo.origin.storageValue())
                        put("role", photo.role.storageValue())
                    }
                )
            }
            productArray.put(productJson(product, photoArray))
        }

        val productsJson = JSONObject().put("products", productArray).toString(2)
        val productsBytes = productsJson.toByteArray(Charsets.UTF_8)

        val manifest = JSONObject().apply {
            put("format", EelensFormat.FORMAT)
            put("formatVersion", EelensFormat.FORMAT_VERSION)
            put("createdBy", JSONObject().put("tool", "EE Lens Android").put("version", appVersion))
            put("createdAt", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.UK)
                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                .format(java.util.Date()))
            put("counts", JSONObject().put("products", products.size).put("photos", photoCount))
            put(
                "recognition",
                JSONObject()
                    .put("modelId", "mobilenet_v3_small")
                    .put("embeddingsIncluded", false)
                    .put("note", "Fingerprints are regenerated after import.")
            )
            put(
                "integrity",
                JSONObject()
                    .put("productsSha256", Hashing.sha256(productsBytes))
                    .put("photoHashes", photoHashes)
            )
        }

        var bytesWritten = 0L
        ZipOutputStream(output.buffered()).use { zip ->
            fun put(name: String, data: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
                bytesWritten += data.size
            }

            put(EelensFormat.MANIFEST, manifest.toString(2).toByteArray(Charsets.UTF_8))
            put(EelensFormat.PRODUCTS, productsBytes)
            payloads.forEachIndexed { index, (path, bytes, _) ->
                put(path, bytes)
                onProgress(index + 1, payloads.size)
            }
        }

        return ExportSummary(products.size, photoCount, bytesWritten)
    }

    private fun productJson(product: Product, photos: JSONArray): JSONObject = JSONObject().apply {
        put("id", product.id)
        put("slug", product.slug)
        put("brand", product.brand)
        put("name", product.name)
        put("model", product.model)
        put("category", product.category ?: JSONObject.NULL)
        put("colour", product.colour ?: JSONObject.NULL)
        put("sizeSweepMm", product.sizeSweepMm ?: JSONObject.NULL)
        put("priceMinor", product.priceMinor ?: JSONObject.NULL)
        put("mrpMinor", product.mrpMinor ?: JSONObject.NULL)
        put("currency", product.currency)
        put("description", product.description)
        put("specs", JSONObject(product.specs.toMap<String, String>()))
        put("coverPhotoId", product.coverPhotoId ?: JSONObject.NULL)
        put("source", product.source.storageValue())
        put("createdAt", product.createdAt)
        put("updatedAt", product.updatedAt)
        put("photos", photos)
    }
}
