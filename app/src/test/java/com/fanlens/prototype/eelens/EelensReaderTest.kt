package com.fanlens.prototype.eelens

import com.fanlens.prototype.model.PhotoRole
import com.fanlens.prototype.util.Hashing
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The reader is what stands between a damaged file and the shop's catalogue.
 * These build real packages on disk and check both what it accepts and, more
 * importantly, what it refuses.
 */
class EelensReaderTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val photoBytes = ByteArray(64) { (it * 7 + 1).toByte() }

    private fun buildPackage(
        formatVersion: Int = EelensFormat.FORMAT_VERSION,
        includePhoto: Boolean = true,
        corruptPhoto: Boolean = false,
        includeProducts: Boolean = true,
        breakProductsChecksum: Boolean = false,
        withMrp: Boolean = true,
        productId: String = "prod-1",
        photoId: String = "photo-1"
    ): File {
        val photoPath = EelensFormat.photoPath(productId, photoId)
        val storedBytes = if (corruptPhoto) ByteArray(64) { 9 } else photoBytes
        val honestHash = Hashing.sha256(photoBytes)

        val photo = JSONObject().apply {
            put("id", photoId); put("fileName", "$photoId.jpg"); put("sha256", honestHash)
            put("width", 800); put("height", 600); put("bytes", photoBytes.size)
            put("sortOrder", 0); put("origin", "import"); put("role", "display")
        }
        val product = JSONObject().apply {
            put("id", productId); put("slug", "test"); put("brand", "Atomberg")
            put("name", "Emperion Magna Smart Ceiling Fan"); put("model", "FG1050")
            put("category", "Ceiling Fan"); put("colour", "Amber Light Teak (Wooden)")
            put("sizeSweepMm", 1280); put("priceMinor", 1_949_800)
            if (withMrp) put("mrpMinor", 3_000_000)
            put("currency", "INR"); put("description", "d")
            put("specs", JSONObject().put("Blades", "3"))
            put("coverPhotoId", photoId); put("source", "user")
            put("createdAt", 1L); put("updatedAt", 2L)
            put("photos", JSONArray().put(photo))
        }
        val productsBytes = JSONObject().put("products", JSONArray().put(product))
            .toString().toByteArray(Charsets.UTF_8)

        val manifest = JSONObject().apply {
            put("format", EelensFormat.FORMAT)
            put("formatVersion", formatVersion)
            put("createdBy", JSONObject().put("tool", "test"))
            put("counts", JSONObject().put("products", 1).put("photos", 1))
            put(
                "integrity",
                JSONObject()
                    .put(
                        "productsSha256",
                        if (breakProductsChecksum) "0".repeat(64) else Hashing.sha256(productsBytes)
                    )
                    .put("photoHashes", JSONObject().put(photoPath, honestHash))
            )
        }

        val file = folder.newFile("pkg-${System.nanoTime()}.eelens")
        ZipOutputStream(file.outputStream()).use { zip ->
            fun put(name: String, data: ByteArray) {
                zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry()
            }
            put(EelensFormat.MANIFEST, manifest.toString().toByteArray(Charsets.UTF_8))
            if (includeProducts) put(EelensFormat.PRODUCTS, productsBytes)
            if (includePhoto) put(photoPath, storedBytes)
        }
        return file
    }

    private fun read(file: File, known: Set<String> = emptySet()) = EelensReader().read(file, known)

    @Test
    fun aGoodPackageReadsEveryField() {
        val staged = read(buildPackage())
        assertEquals(1, staged.preview.products)
        assertEquals(1, staged.preview.photos)
        assertEquals(0, staged.preview.alreadyHere)

        val product = staged.products.single().product
        assertEquals("Atomberg", product.brand)
        assertEquals("Emperion Magna Smart Ceiling Fan", product.name)
        assertEquals("FG1050", product.model)
        assertEquals("Amber Light Teak (Wooden)", product.colour)
        assertEquals(1280, product.sizeSweepMm)
        assertEquals(1_949_800L, product.priceMinor)
        assertEquals(3_000_000L, product.mrpMinor)
        assertEquals(35, product.discountPercent)
        assertEquals(mapOf("Blades" to "3"), product.specs)

        val photo = staged.products.single().photos.single()
        assertEquals(PhotoRole.Display, photo.role)
        assertTrue(photo.bytes.contentEquals(photoBytes))
    }

    @Test
    fun aVersionOneFileWithoutMrpStillImports() {
        // Format 1 predates MRP; its absence means "no MRP", not a failure.
        val product = read(buildPackage(formatVersion = 1, withMrp = false)).products.single().product
        assertNull(product.mrpMinor)
        assertEquals(1_949_800L, product.priceMinor)
        assertNull(product.discountPercent)
    }

    @Test
    fun clashesAreCountedSoTheOwnerCanChoose() {
        val staged = read(buildPackage(), known = setOf("prod-1"))
        assertEquals(1, staged.preview.alreadyHere)
        assertEquals(0, staged.preview.newProducts)
    }

    @Test
    fun aMissingPhotoIsReportedButKeepsTheProduct() {
        val staged = read(buildPackage(includePhoto = false))
        assertEquals(1, staged.preview.missingPhotos.size)
        assertTrue(staged.preview.hasProblems)
        assertEquals(1, staged.products.size)
        assertTrue(staged.products.single().photos.isEmpty())
    }

    @Test
    fun aPhotoThatFailsItsChecksumIsQuarantined() {
        val staged = read(buildPackage(corruptPhoto = true))
        assertEquals(1, staged.preview.corruptPhotos.size)
        assertEquals(1, staged.products.size)
        assertTrue("the damaged photo must not be imported", staged.products.single().photos.isEmpty())
    }

    @Test(expected = EelensException::class)
    fun aNewerFormatIsRefusedOutright() {
        read(buildPackage(formatVersion = EelensFormat.FORMAT_VERSION + 1))
    }

    @Test(expected = EelensException::class)
    fun aTamperedProductListIsRefused() {
        read(buildPackage(breakProductsChecksum = true))
    }

    @Test(expected = EelensException::class)
    fun aPackageWithNoProductListIsRefused() {
        read(buildPackage(includeProducts = false))
    }

    @Test(expected = EelensException::class)
    fun somethingThatIsNotAZipIsRefused() {
        val file = folder.newFile("not-a-zip.eelens")
        file.writeText("this is just some text, definitely not a catalogue")
        read(file)
    }

    @Test(expected = EelensException::class)
    fun anEmptyFileIsRefused() {
        read(folder.newFile("empty.eelens"))
    }

    @Test(expected = EelensException::class)
    fun aTruncatedPackageIsRefused() {
        val good = buildPackage().readBytes()
        val cut = folder.newFile("cut.eelens")
        cut.writeBytes(good.copyOf(good.size / 2))
        read(cut)
    }
}
