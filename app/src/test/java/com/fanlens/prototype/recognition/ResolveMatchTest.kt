package com.fanlens.prototype.recognition

import com.fanlens.prototype.model.MatchSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * resolveMatch is the pure shop-then-catalogue decision behind
 * OnDeviceProductRecognitionEngine.recognize -- exercised here with plain
 * EmbeddingIndex fixtures instead of a Bitmap and a real on-device model,
 * which is why these are the tests for "does adding catalogue photos to the
 * reference set risk shop-shelf recognition" rather than a claim resting on
 * code review alone.
 *
 * One-dimensional fingerprints give an exact, readable score: with a query of
 * [1f], a stored vector of [x] scores exactly x, so every threshold in
 * MatchPolicy can be hit precisely.
 */
class ResolveMatchTest {

    private val query = floatArrayOf(1f)
    private val allKnown: (String) -> Boolean = { true }

    private fun indexOf(vararg entries: Pair<String, Float>): EmbeddingIndex =
        EmbeddingIndex.build(
            dim = 1,
            fingerprints = entries.map { (id, score) -> IndexedFingerprint(id, floatArrayOf(score)) }
        )

    @Test
    fun aConfidentShopMatchIsFoundWithoutTouchingTheCatalogueIndex() {
        val shop = indexOf("fan-a" to .90f, "fan-b" to .10f)

        val decision = resolveMatch(shop, EmbeddingIndex.EMPTY, query, allKnown)

        assertTrue(decision is MatchDecision.Found)
        val found = decision as MatchDecision.Found
        assertEquals("fan-a", found.productId)
        assertEquals(MatchSource.Shop, found.source)
    }

    @Test
    fun aConfidentCatalogueMatchIsFoundWhenShopHasNoCandidates() {
        val catalogue = indexOf("fan-a" to .80f, "fan-b" to .10f)

        val decision = resolveMatch(EmbeddingIndex.EMPTY, catalogue, query, allKnown)

        assertTrue(decision is MatchDecision.Found)
        val found = decision as MatchDecision.Found
        assertEquals("fan-a", found.productId)
        assertEquals(MatchSource.Catalogue, found.source)
    }

    @Test
    fun shopIsAuthoritativeEvenWhenCatalogueWouldAlsoAccept() {
        // Both indexes would independently accept a match here -- for
        // different products -- but shop is judged first and, once accepted,
        // catalogue is never even consulted. This is the guarantee that
        // adding catalogue photos to the reference set cannot make what
        // happens in front of a real shelf any worse.
        val shop = indexOf("fan-a" to .60f, "fan-b" to .10f)
        val catalogue = indexOf("fan-b" to .95f, "fan-a" to .10f)

        val decision = resolveMatch(shop, catalogue, query, allKnown)

        assertTrue(decision is MatchDecision.Found)
        val found = decision as MatchDecision.Found
        assertEquals("fan-a", found.productId)
        assertEquals(MatchSource.Shop, found.source)
    }

    @Test
    fun catalogueFallbackFindsItsOwnProductWhenShopDeclines() {
        val shop = indexOf("fan-a" to .40f, "fan-b" to .35f) // both below SHOP_MINIMUM_SIMILARITY
        val catalogue = indexOf("fan-c" to .80f, "fan-a" to .10f)

        val decision = resolveMatch(shop, catalogue, query, allKnown)

        assertTrue(decision is MatchDecision.Found)
        val found = decision as MatchDecision.Found
        assertEquals("fan-c", found.productId)
        assertEquals(MatchSource.Catalogue, found.source)
    }

    @Test
    fun neitherIndexAcceptsButTheBetterGuessIsShownAsClosest() {
        val shop = indexOf("fan-a" to .55f, "fan-b" to .10f) // just under SHOP_MINIMUM_SIMILARITY
        val catalogue = indexOf("fan-c" to .60f, "fan-a" to .10f) // under CATALOGUE_MINIMUM_SIMILARITY

        val decision = resolveMatch(shop, catalogue, query, allKnown)

        assertTrue(decision is MatchDecision.Closest)
        val closest = decision as MatchDecision.Closest
        assertEquals("fan-c", closest.productId) // catalogue's .60 beats shop's .55
        assertEquals(.60f, closest.score, 0f)
    }

    @Test
    fun aWeakGuessInBothIndexesShowsNothingAtAll() {
        val shop = indexOf("fan-a" to .30f)
        val catalogue = indexOf("fan-b" to .20f)

        val decision = resolveMatch(shop, catalogue, query, allKnown)

        assertEquals(MatchDecision.None, decision)
    }

    @Test
    fun bothIndexesEmptyShowsNothing() {
        val decision = resolveMatch(EmbeddingIndex.EMPTY, EmbeddingIndex.EMPTY, query, allKnown)

        assertEquals(MatchDecision.None, decision)
    }

    @Test
    fun aFingerprintForAProductThatNoLongerExistsIsSkipped() {
        // "ghost" scores highest in both indexes but is not a live product --
        // e.g. deleted after it was fingerprinted, before the index reloaded.
        val shop = indexOf("ghost" to .95f, "fan-a" to .10f)
        val catalogue = indexOf("fan-a" to .80f, "ghost" to .05f)

        val decision = resolveMatch(shop, catalogue, query) { it != "ghost" }

        assertTrue(decision is MatchDecision.Found)
        val found = decision as MatchDecision.Found
        assertEquals("fan-a", found.productId)
        assertEquals(MatchSource.Catalogue, found.source)
    }

    @Test
    fun anAmbiguousShopLeadStillFallsThroughToCatalogue() {
        // fan-a and fan-b are too close together for acceptShop's lead check,
        // so shop declines even though its best score alone would clear the
        // bar -- catalogue still gets its turn.
        val shop = indexOf("fan-a" to .90f, "fan-b" to .899f)
        val catalogue = indexOf("fan-c" to .80f)

        val decision = resolveMatch(shop, catalogue, query, allKnown)

        assertTrue(decision is MatchDecision.Found)
        val found = decision as MatchDecision.Found
        assertEquals("fan-c", found.productId)
        assertEquals(MatchSource.Catalogue, found.source)
    }
}
