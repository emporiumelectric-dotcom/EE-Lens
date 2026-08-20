package com.fanlens.prototype.data

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Sizing rules for stored photos. The recognition model only ever sees 224 px,
 * so these limits exist for how good the product gallery looks, not for accuracy.
 */
object ImageScaling {

    const val MAX_LONG_EDGE = 1024
    const val THUMB_LONG_EDGE = 320
    const val JPEG_QUALITY = 80

    /**
     * Target pixel size for a source image. Images already within the limit are
     * left alone — upscaling a small photo adds bytes without adding detail.
     */
    fun targetSize(width: Int, height: Int, maxLongEdge: Int = MAX_LONG_EDGE): Pair<Int, Int> {
        require(width > 0 && height > 0) { "Image dimensions must be positive." }
        val longEdge = max(width, height)
        if (longEdge <= maxLongEdge) return width to height
        val scale = maxLongEdge.toDouble() / longEdge
        val scaledWidth = (width * scale).roundToInt().coerceAtLeast(1)
        val scaledHeight = (height * scale).roundToInt().coerceAtLeast(1)
        return scaledWidth to scaledHeight
    }

    /**
     * Power-of-two subsampling factor for the first decode pass, so a 12 MP phone
     * photo never has to be fully expanded in memory before being scaled down.
     */
    fun sampleSize(width: Int, height: Int, maxLongEdge: Int = MAX_LONG_EDGE): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        var longEdge = max(width, height)
        while (longEdge / 2 >= maxLongEdge) {
            longEdge /= 2
            sample *= 2
        }
        return sample
    }
}
