package com.fanlens.prototype.recognition

import com.fanlens.prototype.recognition.BrandTextConflict.DetectedText
import com.fanlens.prototype.recognition.BrandTextConflict.TextConflictVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers BrandTextConflict.checkForConflict -- see that object's own doc
 * comment for why this is willing to veto even a confident embedding
 * match (unlike ColorSignature's near-tie-only rule) and why it can only
 * ever reject on POSITIVE conflicting text, never on missing confirmation.
 */
class BrandTextConflictTest {

    private fun text(s: String, area: Float = 0.05f) = DetectedText(s, area)

    @Test
    fun theReportedBug_aProminentDifferentBrandRejectsAConfidentButWrongMatch() {
        // The exact field case: MobileNet confidently (69%) matched a real
        // V-GUARD geyser to "Havells Velora Prime" on shape alone.
        val verdict = BrandTextConflict.checkForConflict(
            matchedBrand = "Havells",
            detections = listOf(text("V-GUARD"))
        )
        assertEquals(TextConflictVerdict.Conflict("V-GUARD"), verdict)
    }

    @Test
    fun theMatchedBrandSOwnTextConfirmsTheMatch() {
        val verdict = BrandTextConflict.checkForConflict(
            matchedBrand = "Havells",
            detections = listOf(text("HAVELLS"))
        )
        assertEquals(TextConflictVerdict.NoConflict, verdict)
    }

    @Test
    fun confirmationIsCaseAndPunctuationInsensitive() {
        assertEquals(
            TextConflictVerdict.NoConflict,
            BrandTextConflict.checkForConflict("V-Guard", listOf(text("v guard")))
        )
        assertEquals(
            TextConflictVerdict.NoConflict,
            BrandTextConflict.checkForConflict("V-Guard", listOf(text("VGUARD")))
        )
    }

    @Test
    fun noTextFoundAtAllNeverRejects() {
        // The common case: most frames never show the product's own brand
        // text legibly at all -- wrong angle, too far, no flat nameplate.
        assertEquals(TextConflictVerdict.NoConflict, BrandTextConflict.checkForConflict("Havells", emptyList()))
    }

    @Test
    fun unrecognisableTextNeverRejects() {
        // A model number, wattage rating, or warranty sticker -- none of it
        // parses as any known brand, so it must never block a match.
        val verdict = BrandTextConflict.checkForConflict(
            matchedBrand = "Havells",
            detections = listOf(text("GHWVVEUMDW10-C"), text("2000W"), text("5 YEAR WARRANTY"))
        )
        assertEquals(TextConflictVerdict.NoConflict, verdict)
    }

    @Test
    fun aConflictingBrandTooSmallInFrameIsIgnored() {
        // A neighbouring product's own label drifting into the edge of the
        // crop -- present, but nowhere near as prominent as the actual
        // product being aimed at would be.
        val verdict = BrandTextConflict.checkForConflict(
            matchedBrand = "Havells",
            detections = listOf(text("V-GUARD", area = 0.001f))
        )
        assertEquals(TextConflictVerdict.NoConflict, verdict)
    }

    @Test
    fun theMatchedBrandWinsEvenWhenAnotherSmallerBrandIsAlsoVisible() {
        // e.g. a "compatible with" sticker, or a nearby accessory's own tiny
        // label -- the matched brand's own prominent text still wins.
        val verdict = BrandTextConflict.checkForConflict(
            matchedBrand = "Havells",
            detections = listOf(text("HAVELLS", area = 0.05f), text("LG", area = 0.001f))
        )
        assertEquals(TextConflictVerdict.NoConflict, verdict)
    }

    @Test
    fun aShortBrandNameMustAppearAsAWholeTokenNotABareSubstring() {
        // "LG" must not fire just because it happens to appear inside
        // garbled OCR output as a bare substring of something unrelated.
        val insideOtherWord = BrandTextConflict.checkForConflict(
            matchedBrand = "Havells",
            detections = listOf(text("ELGIN WATTAGE RATING")) // contains "LG" as a mid-word substring
        )
        assertEquals(TextConflictVerdict.NoConflict, insideOtherWord)

        val asWholeToken = BrandTextConflict.checkForConflict(
            matchedBrand = "Havells",
            detections = listOf(text("LG"))
        )
        assertEquals(TextConflictVerdict.Conflict("LG"), asWholeToken)
    }

    @Test
    fun aMultiWordBrandNameIsMatchedAfterNormalisation() {
        val verdict = BrandTextConflict.checkForConflict(
            matchedBrand = "Havells",
            detections = listOf(text("BLUE STAR"))
        )
        assertEquals(TextConflictVerdict.Conflict("BLUE STAR"), verdict)
    }

    @Test
    fun textSplitAcrossTwoAdjacentBlocksIsStillJoinedAndMatched() {
        // ML Kit commonly returns a brand wordmark as two separate text
        // blocks ("V" and "GUARD") when there's a logo mark between them.
        val verdict = BrandTextConflict.checkForConflict(
            matchedBrand = "Havells",
            detections = listOf(text("V"), text("GUARD"))
        )
        assertEquals(TextConflictVerdict.Conflict("V-GUARD"), verdict)
    }

    @Test
    fun aMatchedBrandNotInTheKnownListIsStillConfirmedByItsOwnText() {
        // Confirmation checks the matched product's own recorded brand
        // directly -- it does not require that brand to be in KNOWN_BRANDS.
        val verdict = BrandTextConflict.checkForConflict(
            matchedBrand = "Longway",
            detections = listOf(text("LONGWAY"))
        )
        assertEquals(TextConflictVerdict.NoConflict, verdict)
    }

    @Test
    fun aMatchedBrandNotInTheKnownListWithNoTextIsStillNeverRejected() {
        val verdict = BrandTextConflict.checkForConflict(matchedBrand = "Longway", detections = emptyList())
        assertEquals(TextConflictVerdict.NoConflict, verdict)
    }

    @Test
    fun theMatchedBrandNeverFlagsItselfAsAConflict() {
        // Defense in depth: even if confirmation's own check were somehow
        // bypassed, the KNOWN_BRANDS loop explicitly skips the matched
        // brand so it can never conflict with itself.
        for (brand in BrandTextConflict.KNOWN_BRANDS) {
            val verdict = BrandTextConflict.checkForConflict(matchedBrand = brand, detections = listOf(text(brand)))
            assertEquals("expected $brand to confirm itself, got $verdict", TextConflictVerdict.NoConflict, verdict)
        }
    }

    @Test
    fun everyKnownBrandCanBeDetectedAsAConflictAgainstADifferentMatch() {
        // Sanity check on the reference list itself: every entry actually
        // fires when it's the one prominently read against some other match.
        for (brand in BrandTextConflict.KNOWN_BRANDS) {
            if (brand == "HAVELLS") continue // the "matched" brand below
            val verdict = BrandTextConflict.checkForConflict(matchedBrand = "Havells", detections = listOf(text(brand)))
            assertTrue("expected $brand to be flagged as a conflict, got $verdict", verdict is TextConflictVerdict.Conflict)
        }
    }
}
