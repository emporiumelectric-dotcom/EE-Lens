package com.fanlens.prototype.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class VectorCodecTest {

    @Test
    fun blobRoundTripPreservesEveryValue() {
        val original = floatArrayOf(0f, 1f, -1f, 0.125f, -37.5f, 1e-6f)
        val decoded = VectorCodec.decode(VectorCodec.encode(original))
        assertEquals(original.size, decoded.size)
        original.indices.forEach { assertEquals(original[it], decoded[it], 0f) }
    }

    @Test
    fun normalisedVectorHasUnitLength() {
        val normalised = VectorCodec.l2Normalize(floatArrayOf(3f, 4f))
        assertEquals(0.6f, normalised[0], 1e-6f)
        assertEquals(0.8f, normalised[1], 1e-6f)
        assertEquals(1f, VectorCodec.dot(normalised, normalised), 1e-6f)
    }

    @Test
    fun allZeroVectorIsLeftAloneRatherThanProducingNaN() {
        val normalised = VectorCodec.l2Normalize(floatArrayOf(0f, 0f, 0f))
        assertTrue(normalised.all { it == 0f })
    }

    @Test
    fun dotOfUnitVectorsMatchesCosineSimilarity() {
        val a = VectorCodec.l2Normalize(floatArrayOf(1f, 2f, 3f))
        val b = VectorCodec.l2Normalize(floatArrayOf(3f, 2f, 1f))
        // cos = (3 + 4 + 3) / 14 = 0.714285…
        assertTrue(abs(VectorCodec.dot(a, b) - 0.7142857f) < 1e-5f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aTruncatedBlobIsRejected() {
        VectorCodec.decode(ByteArray(7))
    }
}

class EmbeddingIndexTest {

    private fun unit(vararg values: Float) = VectorCodec.l2Normalize(floatArrayOf(*values))

    @Test
    fun emptyIndexRanksNothing() {
        assertTrue(EmbeddingIndex.EMPTY.isEmpty)
        assertTrue(EmbeddingIndex.EMPTY.rank(unit(1f, 0f)).isEmpty())
    }

    @Test
    fun aProductScoresAsItsBestMatchingPhoto() {
        val index = EmbeddingIndex.build(
            dim = 2,
            fingerprints = listOf(
                IndexedFingerprint("fan-a", unit(1f, 0f)),
                IndexedFingerprint("fan-a", unit(0f, 1f)),
                IndexedFingerprint("fan-b", unit(-1f, 0f))
            )
        )

        assertEquals(3, index.referenceCount)
        assertEquals(2, index.productCount)

        val ranked = index.rank(unit(1f, 0f))
        assertEquals(2, ranked.size)
        assertEquals("fan-a", ranked.first().productId)
        // Best of fan-a's two photos, not an average of them.
        assertEquals(1f, ranked.first().score, 1e-6f)
        assertEquals("fan-b", ranked.last().productId)
    }

    @Test
    fun rowsFromAnotherModelWidthAreSkippedRatherThanCorruptingTheIndex() {
        val index = EmbeddingIndex.build(
            dim = 2,
            fingerprints = listOf(
                IndexedFingerprint("fan-a", unit(1f, 0f)),
                IndexedFingerprint("stale", floatArrayOf(1f, 0f, 0f, 0f))
            )
        )

        assertEquals(1, index.referenceCount)
        assertNotNull(index.rank(unit(1f, 0f)).firstOrNull { it.productId == "fan-a" })
        assertNull(index.rank(unit(1f, 0f)).firstOrNull { it.productId == "stale" })
    }

    @Test
    fun aQueryOfTheWrongWidthRanksNothingInsteadOfCrashing() {
        val index = EmbeddingIndex.build(2, listOf(IndexedFingerprint("fan-a", unit(1f, 0f))))
        assertTrue(index.rank(floatArrayOf(1f, 0f, 0f)).isEmpty())
    }
}
