package com.fanlens.prototype.recognition

import android.graphics.Bitmap

/**
 * The single definition of how an image is prepared before it is fingerprinted.
 *
 * Camera frames and stored reference photos must go through exactly the same
 * steps. They did not used to: frames were centre-cropped to a square while
 * reference photos were embedded whole, so a portrait shop photo was squashed
 * to 224x224 in a way a square camera crop never is. Comparing the two was
 * comparing fingerprints taken through different lenses.
 *
 * Measured on the real catalogue, aligning them raised top-1 accuracy from
 * 20/30 to 22/30 and — more importantly — kept accuracy from collapsing as the
 * acceptance threshold rises.
 */
object RecognitionPreprocessing {

    /**
     * Bump when the steps below change. It forms part of the stored model
     * version, so existing fingerprints are discarded and regenerated rather
     * than silently compared against differently-prepared ones.
     */
    const val VERSION = "crop92"

    /** Fraction of the short edge kept. Trims frame edges without cropping the product. */
    const val CENTRE_FRACTION = 0.92f

    /**
     * Centre square crop. Returns the original bitmap when it is already the
     * right shape, so callers must not assume they were given a new instance.
     */
    fun centreSquare(bitmap: Bitmap, fraction: Float = CENTRE_FRACTION): Bitmap {
        val side = (minOf(bitmap.width, bitmap.height) * fraction).toInt().coerceAtLeast(1)
        if (side == bitmap.width && side == bitmap.height) return bitmap
        return Bitmap.createBitmap(
            bitmap,
            (bitmap.width - side) / 2,
            (bitmap.height - side) / 2,
            side,
            side
        )
    }
}
