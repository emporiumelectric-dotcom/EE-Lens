package com.fanlens.prototype.data

import com.fanlens.prototype.util.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeedPolicyTest {

    @Test
    fun seedRunsOnceAndNotAgainAfterAnUpgrade() {
        assertTrue(SeedPolicy.shouldRun(0))
        assertFalse(SeedPolicy.shouldRun(SeedPolicy.SEED_VERSION))
        assertFalse(SeedPolicy.shouldRun(SeedPolicy.SEED_VERSION + 1))
    }

    @Test
    fun productsAlreadyStoredAreNotSeededTwice() {
        val bundled = listOf("fan-a", "fan-b", "fan-c")
        assertEquals(listOf("fan-c"), SeedPolicy.idsToSeed(bundled, setOf("fan-a", "fan-b")))
    }

    @Test
    fun aProductTheOwnerDeletedStaysDeleted() {
        // Soft-deleted rows are still "known", so seeding must skip them.
        val bundled = listOf("fan-a", "fan-b")
        assertTrue(SeedPolicy.idsToSeed(bundled, setOf("fan-a", "fan-b")).isEmpty())
    }
}

class ImageScalingTest {

    @Test
    fun largePhotosAreScaledToTheLongEdgeLimit() {
        val (width, height) = ImageScaling.targetSize(4000, 3000, 1024)
        assertEquals(1024, width)
        assertEquals(768, height)
    }

    @Test
    fun portraitPhotosScaleOnTheirOwnLongEdge() {
        val (width, height) = ImageScaling.targetSize(3000, 4000, 1024)
        assertEquals(768, width)
        assertEquals(1024, height)
    }

    @Test
    fun smallPhotosAreLeftAloneRatherThanUpscaled() {
        assertEquals(600 to 400, ImageScaling.targetSize(600, 400, 1024))
    }

    @Test
    fun sampleSizeNeverDropsBelowTheTargetResolution() {
        assertEquals(1, ImageScaling.sampleSize(1200, 900, 1024))
        assertEquals(2, ImageScaling.sampleSize(2048, 1536, 1024))
        assertEquals(4, ImageScaling.sampleSize(4096, 3072, 1024))
    }
}

class MoneyTest {

    @Test
    fun rupeesUseIndianDigitGrouping() {
        assertEquals("₹4,250", Money.format(425_000, "INR"))
        assertEquals("₹12,345", Money.format(1_234_500, "INR"))
        assertEquals("₹1,23,456", Money.format(12_345_600, "INR"))
    }

    @Test
    fun paiseAreShownOnlyWhenThereAreAny() {
        assertEquals("₹4,250", Money.format(425_000, "INR"))
        assertEquals("₹4,250.50", Money.format(425_050, "INR"))
        assertEquals("₹4,250.05", Money.format(425_005, "INR"))
    }

    @Test
    fun noPriceMeansNoLabelRatherThanZero() {
        assertNull(Money.format(null, "INR"))
    }

    @Test
    fun ownerTypedPricesParseBackToWholePaise() {
        assertEquals(425_000L, Money.parseToMinor("4250"))
        assertEquals(425_000L, Money.parseToMinor("4,250"))
        assertEquals(425_050L, Money.parseToMinor("4250.50"))
        assertEquals(425_000L, Money.parseToMinor("₹4250"))
        assertNull(Money.parseToMinor("not a price"))
        assertNull(Money.parseToMinor(""))
    }
}
