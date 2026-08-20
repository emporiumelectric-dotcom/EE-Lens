package com.fanlens.prototype.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two indexes are judged independently. Shop matching decides what happens
 * in front of a real shelf; catalogue matching only ever runs after shop
 * matching has declined, and answers a different question.
 */
class MatchPolicyTest {

    @Test
    fun theCatalogueBarIsStrictlyHigherThanTheShopBar() {
        assertTrue(
            "a catalogue match must be harder to earn than a shop match",
            MatchPolicy.CATALOGUE_MINIMUM_SIMILARITY > MatchPolicy.SHOP_MINIMUM_SIMILARITY
        )
    }

    @Test
    fun theMeasuredThresholdsAreWhatShipped() {
        assertEquals(.56f, MatchPolicy.SHOP_MINIMUM_SIMILARITY, 0f)
        assertEquals(.74f, MatchPolicy.CATALOGUE_MINIMUM_SIMILARITY, 0f)
    }

    @Test
    fun shopMatchingIsUnchangedByTheCatalogueIndex() {
        assertFalse(MatchPolicy.acceptShop(.55f, .10f))
        assertTrue(MatchPolicy.acceptShop(.56f, .10f))
        assertFalse("neck and neck is still ambiguous", MatchPolicy.acceptShop(.80f, .799f))
    }

    @Test
    fun aScoreGoodEnoughForTheShelfIsNotGoodEnoughForAScreen() {
        // .60 was a confident shelf match in the measurements; on a screen it
        // sits inside the range where wrong products also scored.
        assertTrue(MatchPolicy.acceptShop(.60f, .10f))
        assertFalse(MatchPolicy.acceptCatalogue(.60f, .10f))
    }

    @Test
    fun catalogueMatchingAcceptsTheMeasuredCorrectRange() {
        // Correct screen matches scored no lower than .750 on the real catalogue.
        assertTrue(MatchPolicy.acceptCatalogue(.75f, .40f))
        assertTrue(MatchPolicy.acceptCatalogue(.86f, .47f))
    }

    @Test
    fun catalogueMatchingRejectsTheMeasuredWrongCeiling() {
        // The best wrong product reached .712; it must stay below the line.
        assertFalse(MatchPolicy.acceptCatalogue(.712f, .40f))
        assertFalse(MatchPolicy.acceptCatalogue(.739f, .40f))
    }

    @Test
    fun anAmbiguousCatalogueMatchIsStillRefused() {
        assertFalse(MatchPolicy.acceptCatalogue(.90f, .899f))
    }
}
