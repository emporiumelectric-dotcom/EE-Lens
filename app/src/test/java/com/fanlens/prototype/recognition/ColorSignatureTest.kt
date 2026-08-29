package com.fanlens.prototype.recognition

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the histogram math itself (fromPixels/similarity), and the two
 * tie-break decisions (isNearTie/preferRunnerUpByColor) this is built to
 * eventually slot into -- see ColorSignature's own doc comment for why
 * that wiring isn't in this change. The property every test here ladders
 * up to is the one that actually matters for shipping this safely: colour
 * can only ever be consulted once the embedding score alone is already
 * too close to call, never override a confident match.
 */
class ColorSignatureTest {

    private fun solidColor(argb: Int, count: Int = 100): IntArray = IntArray(count) { argb }

    // ---------------- fromPixels ----------------

    @Test
    fun aSolidColorImageConcentratesEntirelyInOneBin() {
        val signature = ColorSignature.fromPixels(solidColor(0xFF_FF0000.toInt())) // pure red

        assertEquals(1, signature.count { it > 0f })
        assertEquals(1f, signature.sum(), 0.0001f)
    }

    @Test
    fun anEmptyPixelArrayReturnsAnAllZeroSignatureNotNaN() {
        val signature = ColorSignature.fromPixels(IntArray(0))

        assertEquals(ColorSignature.SIGNATURE_SIZE, signature.size)
        assertTrue(signature.all { it == 0f })
    }

    @Test
    fun everySignatureIsNormalisedToSumToOne() {
        val pixels = solidColor(0xFF_FF0000.toInt(), 40) + solidColor(0xFF_0000FF.toInt(), 60)

        val signature = ColorSignature.fromPixels(pixels)

        assertEquals(1f, signature.sum(), 0.0001f)
    }

    @Test
    fun alphaIsIgnored() {
        val opaqueRed = ColorSignature.fromPixels(solidColor(0xFF_FF0000.toInt()))
        val translucentRed = ColorSignature.fromPixels(solidColor(0x80_FF0000.toInt()))

        assertArrayEquals(opaqueRed, translucentRed, 0.0001f)
    }

    // ---------------- similarity ----------------

    @Test
    fun aSignatureIsIdenticalToItself() {
        val signature = ColorSignature.fromPixels(solidColor(0xFF_336699.toInt()))

        assertEquals(1f, ColorSignature.similarity(signature, signature), 0.0001f)
    }

    @Test
    fun twoDisjointSolidColoursShareNothing() {
        val red = ColorSignature.fromPixels(solidColor(0xFF_FF0000.toInt()))
        val blue = ColorSignature.fromPixels(solidColor(0xFF_0000FF.toInt()))

        assertEquals(0f, ColorSignature.similarity(red, blue), 0.0001f)
    }

    @Test
    fun aHalfSharedMixScoresHalfway() {
        // 100 red pixels vs. a 50/50 red-blue mix: half of the query colour
        // (red) is present in both, half (blue) only in the second -- the
        // hand-computed intersection is exactly 0.5.
        val allRed = ColorSignature.fromPixels(solidColor(0xFF_FF0000.toInt(), 100))
        val halfRedHalfBlue = ColorSignature.fromPixels(
            solidColor(0xFF_FF0000.toInt(), 50) + solidColor(0xFF_0000FF.toInt(), 50)
        )

        assertEquals(0.5f, ColorSignature.similarity(allRed, halfRedHalfBlue), 0.0001f)
    }

    // ---------------- isNearTie ----------------

    @Test
    fun aDecisiveEmbeddingLeadIsNeverANearTie() {
        // The overwhelming majority of real matches: colour must never even
        // be consulted here, so a confident match can never be second-guessed.
        assertFalse(ColorSignature.isNearTie(bestScore = .80f, runnerUpScore = .10f))
        assertFalse(ColorSignature.isNearTie(bestScore = .60f, runnerUpScore = .50f))
    }

    @Test
    fun scoresWithinTheNearTieBandAreFlaggedForAColourCheck() {
        assertTrue(ColorSignature.isNearTie(bestScore = .60f, runnerUpScore = .58f))
        assertTrue(ColorSignature.isNearTie(bestScore = .60f, runnerUpScore = .60f))
    }

    @Test
    fun aRunnerUpAheadOfTheBestIsStillANearTieNotAnError() {
        // rank() always orders best >= runnerUp by construction, but this
        // function's own contract shouldn't silently assume that never
        // changes -- a negative gap is exactly as "too close to call" as
        // a small positive one.
        assertTrue(ColorSignature.isNearTie(bestScore = .58f, runnerUpScore = .60f))
    }

    // ---------------- preferRunnerUpByColor ----------------

    @Test
    fun theRunnerUpIsPreferredWhenItsColourClearlyMatchesTheQueryBetter() {
        val query = ColorSignature.fromPixels(solidColor(0xFF_FF0000.toInt()))
        val bestColor = ColorSignature.fromPixels(solidColor(0xFF_0000FF.toInt())) // wrong colour entirely
        val runnerUpColor = ColorSignature.fromPixels(solidColor(0xFF_FF0000.toInt())) // exact colour match

        assertTrue(ColorSignature.preferRunnerUpByColor(query, bestColor, runnerUpColor))
    }

    @Test
    fun theCurrentBestIsKeptWhenItsColourAlreadyMatchesBetter() {
        val query = ColorSignature.fromPixels(solidColor(0xFF_FF0000.toInt()))
        val bestColor = ColorSignature.fromPixels(solidColor(0xFF_FF0000.toInt()))
        val runnerUpColor = ColorSignature.fromPixels(solidColor(0xFF_0000FF.toInt()))

        assertFalse(ColorSignature.preferRunnerUpByColor(query, bestColor, runnerUpColor))
    }

    @Test
    fun anAmbiguousColourDifferenceDoesNotOverrideTheEmbeddingEitherWay() {
        // Both candidates are close enough in colour to the query that
        // neither has a clear enough lead (see MINIMUM_COLOR_LEAD) --
        // colour must not flip a coin here any more than the embedding
        // score does at MatchPolicy.MINIMUM_LEAD.
        val query = ColorSignature.fromPixels(
            solidColor(0xFF_FF0000.toInt(), 50) + solidColor(0xFF_0000FF.toInt(), 50)
        )
        val bestColor = ColorSignature.fromPixels(
            solidColor(0xFF_FF0000.toInt(), 52) + solidColor(0xFF_0000FF.toInt(), 48)
        )
        val runnerUpColor = ColorSignature.fromPixels(
            solidColor(0xFF_FF0000.toInt(), 48) + solidColor(0xFF_0000FF.toInt(), 52)
        )

        assertFalse(ColorSignature.preferRunnerUpByColor(query, bestColor, runnerUpColor))
    }
}
