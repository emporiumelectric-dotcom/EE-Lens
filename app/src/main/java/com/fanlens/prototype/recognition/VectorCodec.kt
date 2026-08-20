package com.fanlens.prototype.recognition

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Fingerprints are stored as little-endian float32 blobs, L2-normalised at write
 * time. Normalising once on save turns the per-frame cosine similarity into a
 * plain dot product, which is what [dot] relies on.
 */
object VectorCodec {

    const val BYTES_PER_VALUE = 4

    fun encode(vector: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(vector.size * BYTES_PER_VALUE).order(ByteOrder.LITTLE_ENDIAN)
        vector.forEach(buffer::putFloat)
        return buffer.array()
    }

    fun decode(bytes: ByteArray): FloatArray {
        require(bytes.size % BYTES_PER_VALUE == 0) {
            "A fingerprint blob must be a whole number of float values, got ${bytes.size} bytes."
        }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / BYTES_PER_VALUE) { buffer.getFloat() }
    }

    /**
     * Returns a unit-length copy. An all-zero vector has no direction to preserve,
     * so it is returned unchanged rather than producing NaNs.
     */
    fun l2Normalize(vector: FloatArray): FloatArray {
        var sum = 0.0
        for (value in vector) sum += value.toDouble() * value.toDouble()
        if (sum <= 0.0) return vector.copyOf()
        val scale = (1.0 / sqrt(sum)).toFloat()
        return FloatArray(vector.size) { vector[it] * scale }
    }

    /** Cosine similarity, given both inputs are already unit length. */
    fun dot(a: FloatArray, b: FloatArray, bOffset: Int = 0): Float {
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[bOffset + i]
        return sum
    }
}
