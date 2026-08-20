package com.fanlens.prototype.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoRoleTest {

    private fun photo(id: String, role: PhotoRole, order: Int = 0) = Photo(
        id = id,
        productId = "p",
        fileName = "$id.jpg",
        sha256 = id,
        width = 1024,
        height = 768,
        bytes = 1000,
        sortOrder = order,
        origin = PhotoOrigin.Gallery,
        role = role,
        createdAt = 0
    )

    private val product = Product(id = "p", name = "N", model = "M", description = "D")

    @Test
    fun cameraAndGalleryPhotosDefaultToRecognition() {
        assertEquals(PhotoRole.Recognition, PhotoRole.defaultFor(PhotoOrigin.Camera))
        assertEquals(PhotoRole.Recognition, PhotoRole.defaultFor(PhotoOrigin.Gallery))
        assertEquals(PhotoRole.Recognition, PhotoRole.defaultFor(PhotoOrigin.Bundled))
    }

    @Test
    fun downloadedAndImportedPhotosDefaultToDisplay() {
        assertEquals(PhotoRole.Display, PhotoRole.defaultFor(PhotoOrigin.File))
        assertEquals(PhotoRole.Display, PhotoRole.defaultFor(PhotoOrigin.Import))
    }

    @Test
    fun unknownStoredValuesFallBackToRecognition() {
        // An older row written before roles existed must keep being matched.
        assertEquals(PhotoRole.Recognition, PhotoRole.fromStorage(null))
        assertEquals(PhotoRole.Recognition, PhotoRole.fromStorage("something-new"))
        assertEquals(PhotoRole.Display, PhotoRole.fromStorage("display"))
    }

    @Test
    fun theGalleryPrefersCataloguePhotos() {
        val details = ProductWithPhotos(
            product = product,
            photos = listOf(
                photo("shop1", PhotoRole.Recognition),
                photo("clean1", PhotoRole.Display),
                photo("shop2", PhotoRole.Recognition)
            )
        )
        assertEquals(listOf("clean1"), details.galleryPhotos.map { it.id })
        assertEquals(2, details.recognitionPhotos.size)
    }

    @Test
    fun aProductWithOnlyShopPhotosStillHasAGalleryAndCover() {
        val details = ProductWithPhotos(
            product = product,
            photos = listOf(photo("shop1", PhotoRole.Recognition), photo("shop2", PhotoRole.Recognition))
        )
        assertEquals(2, details.galleryPhotos.size)
        assertEquals("shop1", details.coverPhoto?.id)
    }

    @Test
    fun anExplicitCoverIsHonouredAmongCataloguePhotos() {
        val details = ProductWithPhotos(
            product = product.copy(coverPhotoId = "clean2"),
            photos = listOf(
                photo("clean1", PhotoRole.Display),
                photo("clean2", PhotoRole.Display)
            )
        )
        assertEquals("clean2", details.coverPhoto?.id)
    }

    @Test
    fun aCataloguePhotoBeatsAShopPhotoEvenWhenTheShopPhotoIsTheStoredCover() {
        // Customers must never be shown a shelf snapshot while a clean image exists.
        val details = ProductWithPhotos(
            product = product.copy(coverPhotoId = "shop2"),
            photos = listOf(
                photo("clean1", PhotoRole.Display),
                photo("shop2", PhotoRole.Recognition)
            )
        )
        assertEquals("clean1", details.coverPhoto?.id)
    }

    @Test
    fun aShopPhotoIsUsedOnlyWhenThereAreNoCataloguePhotos() {
        val details = ProductWithPhotos(
            product = product.copy(coverPhotoId = "shop2"),
            photos = listOf(photo("shop1", PhotoRole.Recognition), photo("shop2", PhotoRole.Recognition))
        )
        assertEquals("shop2", details.coverPhoto?.id)
    }

    @Test
    fun aProductWithNoPhotosHasNoCover() {
        assertTrue(ProductWithPhotos(product, emptyList()).galleryPhotos.isEmpty())
        assertEquals(null, ProductWithPhotos(product, emptyList()).coverPhoto)
    }
}
