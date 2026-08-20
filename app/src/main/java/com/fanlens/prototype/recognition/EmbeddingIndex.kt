package com.fanlens.prototype.recognition

/** One stored reference fingerprint, already unit length. */
data class IndexedFingerprint(
    val productId: String,
    val vector: FloatArray
) {
    override fun equals(other: Any?): Boolean =
        other is IndexedFingerprint && productId == other.productId && vector.contentEquals(other.vector)

    override fun hashCode(): Int = 31 * productId.hashCode() + vector.contentHashCode()
}

data class ScoredProduct(val productId: String, val score: Float)

/**
 * All reference fingerprints held in one flat array so a camera frame can be
 * scored against the whole catalogue without allocating per row.
 *
 * A product scores as its single best-matching photo, which is how the original
 * prototype behaved.
 */
class EmbeddingIndex private constructor(
    val dim: Int,
    private val productIds: Array<String>,
    private val vectors: FloatArray
) {

    val referenceCount: Int get() = productIds.size

    val productCount: Int get() = productIds.distinct().size

    val isEmpty: Boolean get() = productIds.isEmpty()

    /**
     * Best score per product, highest first. Returns an empty list when the index
     * holds nothing or the query has the wrong width for this model.
     */
    fun rank(query: FloatArray): List<ScoredProduct> {
        if (isEmpty || query.size != dim) return emptyList()

        val best = HashMap<String, Float>(productCount * 2)
        for (row in productIds.indices) {
            val score = VectorCodec.dot(query, vectors, row * dim)
            val productId = productIds[row]
            val previous = best[productId]
            if (previous == null || score > previous) best[productId] = score
        }
        return best.entries
            .map { ScoredProduct(it.key, it.value) }
            .sortedByDescending(ScoredProduct::score)
    }

    companion object {
        val EMPTY = EmbeddingIndex(0, emptyArray(), FloatArray(0))

        /**
         * Builds an index from stored fingerprints, skipping any whose width does
         * not match [dim]. A stale row left by an older model is dropped rather
         * than corrupting every comparison.
         */
        fun build(dim: Int, fingerprints: List<IndexedFingerprint>): EmbeddingIndex {
            if (dim <= 0) return EMPTY
            val usable = fingerprints.filter { it.vector.size == dim }
            if (usable.isEmpty()) return EMPTY

            val ids = Array(usable.size) { usable[it].productId }
            val flat = FloatArray(usable.size * dim)
            usable.forEachIndexed { row, fingerprint ->
                fingerprint.vector.copyInto(flat, row * dim)
            }
            return EmbeddingIndex(dim, ids, flat)
        }
    }
}
