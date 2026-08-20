package com.fanlens.prototype.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductRecognitionTest {
    @Test
    fun catalogueContainsSixUniqueProductsWithPhotos() {
        val entries = BundledProductCatalog.entries

        assertEquals(6, entries.size)
        assertEquals(entries.size, entries.map { it.product.id }.distinct().size)
        assertTrue(entries.all { it.referenceAssets.size >= 4 })
    }

    @Test
    fun everyBundledProductCarriesSeedMetadata() {
        BundledProductCatalog.entries.forEach { entry ->
            val product = entry.product
            assertTrue("${product.id} is missing a brand", product.brand.isNotBlank())
            assertTrue("${product.id} is missing a slug", product.slug.isNotBlank())
            assertEquals("Ceiling fan", product.category)
        }
    }

    // Acceptance thresholds are covered in MatchPolicyTest, which tests the shop
    // and catalogue indexes independently.
}
