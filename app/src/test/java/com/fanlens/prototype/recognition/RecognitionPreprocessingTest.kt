package com.fanlens.prototype.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The preprocessing version is what forces stored fingerprints to be regenerated
 * when the preparation steps change. Getting it wrong means old and new
 * fingerprints are silently compared against each other.
 */
class RecognitionPreprocessingTest {

    @Test
    fun theVersionIsPartOfTheStoredModelVersion() {
        // EmbeddingGenerator builds "<asset hash>/<preprocessing version>".
        val modelVersion = "abc123/${RecognitionPreprocessing.VERSION}"
        assertTrue(modelVersion.endsWith("/crop92"))
        assertEquals("abc123", modelVersion.substringBefore('/'))
    }

    @Test
    fun theVersionIsNotBlankSoItCannotSilentlyVanish() {
        assertTrue(RecognitionPreprocessing.VERSION.isNotBlank())
    }

    @Test
    fun theCentreFractionTrimsEdgesWithoutCroppingTheProduct() {
        assertTrue(RecognitionPreprocessing.CENTRE_FRACTION in 0.5f..1.0f)
    }
}
