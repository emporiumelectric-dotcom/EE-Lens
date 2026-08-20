package com.fanlens.prototype.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import com.fanlens.prototype.util.Hashing
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/** A compressed image held in memory, before anything is written to disk. */
data class EncodedImage(
    val fullBytes: ByteArray,
    val thumbBytes: ByteArray,
    val width: Int,
    val height: Int,
    val sha256: String
) {
    override fun equals(other: Any?): Boolean =
        other is EncodedImage && sha256 == other.sha256 && width == other.width && height == other.height

    override fun hashCode(): Int = 31 * (31 * sha256.hashCode() + width) + height
}

/** What ended up on disk. */
data class StoredImage(
    val fileName: String,
    val sha256: String,
    val width: Int,
    val height: Int,
    val bytes: Long
)

/**
 * Owns every product image on disk.
 *
 * Everything lives in app-private storage — never the shared gallery, never
 * external storage — so nothing leaks into the phone's photo roll and nothing
 * needs a storage permission. Imported photos are copied in, so the gallery
 * keeps working after the original download is deleted.
 *
 * Encoding is deliberately separate from writing: the hash is only known after
 * compression, and a duplicate must be rejected before any file appears.
 */
class PhotoStore(context: Context) {

    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, ROOT)

    fun productDir(productId: String): File = File(root, productId)

    fun fullFile(productId: String, photoId: String): File =
        File(productDir(productId), "$photoId.jpg")

    fun thumbFile(productId: String, photoId: String): File =
        File(productDir(productId), "${photoId}$THUMB_SUFFIX.jpg")

    fun exists(productId: String, photoId: String): Boolean =
        fullFile(productId, photoId).isFile

    /**
     * Reads, corrects orientation, scales to at most 1024 px on the long edge and
     * compresses. Nothing is written yet.
     *
     * The hash covers the compressed bytes, so the same photo arriving twice by
     * different routes — gallery once, Downloads once — still collides.
     */
    fun encode(open: () -> InputStream): EncodedImage {
        val source = open().use(InputStream::readBytes)
        require(source.isNotEmpty()) { "The selected file is empty." }

        val rotation = readRotation(source)
        val decoded = decodeScaled(source, ImageScaling.MAX_LONG_EDGE)
            ?: throw IllegalArgumentException("This file could not be read as an image.")

        val upright = applyRotation(decoded, rotation)
        val full = resizeTo(upright, ImageScaling.MAX_LONG_EDGE)
        val fullBytes = compress(full)
        val thumb = resizeTo(full, ImageScaling.THUMB_LONG_EDGE)
        val thumbBytes = compress(thumb)

        val width = full.width
        val height = full.height
        if (thumb !== full) thumb.recycle()
        if (full !== upright) full.recycle()
        if (upright !== decoded) upright.recycle()
        decoded.recycle()

        return EncodedImage(
            fullBytes = fullBytes,
            thumbBytes = thumbBytes,
            width = width,
            height = height,
            sha256 = Hashing.sha256(fullBytes)
        )
    }

    /** Writes an already-encoded image. Creates the product folder if needed. */
    fun write(productId: String, photoId: String, encoded: EncodedImage): StoredImage {
        val dir = productDir(productId)
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw IllegalStateException("Could not create storage for this product.")
        }

        val target = fullFile(productId, photoId)
        target.writeBytes(encoded.fullBytes)
        thumbFile(productId, photoId).writeBytes(encoded.thumbBytes)

        return StoredImage(
            fileName = target.name,
            sha256 = encoded.sha256,
            width = encoded.width,
            height = encoded.height,
            bytes = encoded.fullBytes.size.toLong()
        )
    }

    /** Encode and write in one step, for callers with nothing to check in between. */
    fun store(productId: String, photoId: String, open: () -> InputStream): StoredImage =
        write(productId, photoId, encode(open))

    /**
     * Writes bytes that are already in stored form, straight from a catalogue
     * package. The thumbnail is regenerated locally so the gallery has one.
     */
    fun writeRaw(productId: String, photoId: String, bytes: ByteArray): StoredImage {
        val dir = productDir(productId)
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw IllegalStateException("Could not create storage for this product.")
        }
        val target = fullFile(productId, photoId)
        target.writeBytes(bytes)

        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        if (decoded != null) {
            val thumb = resizeTo(decoded, ImageScaling.THUMB_LONG_EDGE)
            thumbFile(productId, photoId).writeBytes(compress(thumb))
            if (thumb !== decoded) thumb.recycle()
            decoded.recycle()
        }

        return StoredImage(
            fileName = target.name,
            sha256 = Hashing.sha256(bytes),
            width = decoded?.width ?: 0,
            height = decoded?.height ?: 0,
            bytes = bytes.size.toLong()
        )
    }

    /** Loads a stored photo for recognition; null when the file is gone. */
    fun loadFull(productId: String, photoId: String): Bitmap? {
        val file = fullFile(productId, photoId)
        if (!file.isFile) return null
        return runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
    }

    fun delete(productId: String, photoId: String) {
        fullFile(productId, photoId).delete()
        thumbFile(productId, photoId).delete()
    }

    fun deleteProduct(productId: String) {
        productDir(productId).deleteRecursively()
    }

    /** Scratch file the camera app writes a pending capture into. */
    fun newCaptureFile(): File {
        val dir = File(appContext.cacheDir, CAPTURES)
        dir.mkdirs()
        return File(dir, "capture-${System.currentTimeMillis()}.jpg")
    }

    fun clearCaptures() {
        File(appContext.cacheDir, CAPTURES).deleteRecursively()
    }

    /** Total bytes held by all product photos, for the storage figure shown to the owner. */
    fun usedBytes(): Long =
        if (root.isDirectory) root.walkTopDown().filter(File::isFile).sumOf(File::length) else 0L

    private fun decodeScaled(source: ByteArray, maxLongEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = ImageScaling.sampleSize(bounds.outWidth, bounds.outHeight, maxLongEdge)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(source, 0, source.size, options)
    }

    private fun readRotation(source: ByteArray): Int = runCatching {
        val exif = ByteArrayInputStream(source).use(::ExifInterface)
        when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }.getOrDefault(0)

    private fun applyRotation(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun resizeTo(bitmap: Bitmap, maxLongEdge: Int): Bitmap {
        val (width, height) = ImageScaling.targetSize(bitmap.width, bitmap.height, maxLongEdge)
        if (width == bitmap.width && height == bitmap.height) return bitmap
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    private fun compress(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, ImageScaling.JPEG_QUALITY, out)
        return out.toByteArray()
    }

    private companion object {
        const val ROOT = "products"
        const val CAPTURES = "captures"
        const val THUMB_SUFFIX = "_t"
    }
}
