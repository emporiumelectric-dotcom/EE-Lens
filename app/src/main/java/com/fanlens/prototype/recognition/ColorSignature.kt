package com.fanlens.prototype.recognition

/**
 * A lightweight, on-device "what colour is this, roughly" fingerprint --
 * a coarse RGB histogram, comparable to another with one cheap similarity
 * score. Written from investigating why structurally-identical products
 * that differ only in colour/finish (e.g. several Havells Enticer
 * variants, each with its own product entry and its own shop photos) are
 * sometimes confused for each other by recognition.
 *
 * Why: EmbeddingGenerator's model (MobileNetV3-Small, a general
 * classification backbone -- see its own doc comment) is trained toward
 * some *invariance* to colour and lighting shifts of the same object
 * class, since that helps classify "a fan" as a fan regardless of which
 * colour it ships in. That is exactly backwards for this app's job: two
 * fans that are the *same* shape but different SKUs need to be told
 * apart by colour precisely because nothing else about them differs.
 * RecognitionPreprocessing confirms the colour information itself is
 * never stripped before embedding (no greyscale conversion, just a
 * centre-square crop) -- it reaches the model fine, the model's own
 * learned feature space just doesn't reliably preserve it. And
 * MatchPolicy's acceptance margin (MINIMUM_LEAD, 0.003) is thin enough
 * that two near-identical embeddings -- exactly what two colour variants
 * of the same base model would produce -- can flip which one "wins" on
 * essentially no signal at all today.
 *
 * This is intentionally independent of Bitmap/Android so the histogram
 * math is fully unit-testable in this repo's plain JVM test suite --
 * android.graphics.Bitmap is not usable there, same reason
 * RecognitionPreprocessing's own logic is written the way it is. Real use
 * reads pixels via Bitmap.getPixels() into the same ARGB Int layout
 * [fromPixels] expects.
 *
 * NOT wired into live recognition (OnDeviceProductRecognitionEngine /
 * MatchPolicy) in this change -- see this repo's PR description for why:
 * doing that safely needs a schema change (storing each reference photo's
 * signature alongside its embedding, computed once at fingerprint time so
 * no extra disk I/O ever lands on the live camera path) and real-device
 * measurement against actual colour-variant photos, neither of which can
 * be done or verified from this sandbox. [preferRunnerUpByColor] and
 * [isNearTie] below are the exact decision this is designed to slot into
 * once that plumbing exists -- both pure and already fully tested against
 * the one property that matters most: a confidently-scoring match is
 * never second-guessed, because colour is only ever consulted once the
 * embedding score alone is already too close to call.
 */
object ColorSignature {

    /** Bins per RGB channel. 4 -> 64 buckets total -- a rough colour fingerprint, not a photo comparison. */
    const val BINS_PER_CHANNEL = 4
    const val SIGNATURE_SIZE = BINS_PER_CHANNEL * BINS_PER_CHANNEL * BINS_PER_CHANNEL

    /**
     * A normalised (sums to 1) histogram over the quantised RGB colour of
     * every pixel in [argbPixels] -- the same 0xAARRGGBB-per-element layout
     * Bitmap.getPixels() produces; alpha is ignored. An empty input returns
     * an all-zero signature rather than dividing by zero.
     */
    fun fromPixels(argbPixels: IntArray): FloatArray {
        val signature = FloatArray(SIGNATURE_SIZE)
        if (argbPixels.isEmpty()) return signature
        for (pixel in argbPixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val bin = binOf(r) * BINS_PER_CHANNEL * BINS_PER_CHANNEL + binOf(g) * BINS_PER_CHANNEL + binOf(b)
            signature[bin] += 1f
        }
        val total = argbPixels.size.toFloat()
        for (i in signature.indices) signature[i] /= total
        return signature
    }

    private fun binOf(channel: Int): Int =
        (channel * BINS_PER_CHANNEL / 256).coerceIn(0, BINS_PER_CHANNEL - 1)

    /**
     * Histogram intersection: how much of [a] and [b]'s colour is shared,
     * in [0, 1] -- 1 means identical distributions, 0 means no colour in
     * common at all. Chosen over e.g. cosine similarity because it directly
     * answers "how much of this colour do both photos actually have" and
     * stays meaningful even when one photo is mostly background and the
     * other isn't, which cosine similarity does not handle as cleanly for
     * two already-normalised distributions like these.
     */
    fun similarity(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "Signatures must be the same size to compare (${a.size} vs ${b.size})." }
        var sum = 0f
        for (i in a.indices) sum += minOf(a[i], b[i])
        return sum.coerceIn(0f, 1f)
    }

    /**
     * How close two embedding scores need to be before colour is worth
     * consulting at all. Deliberately much wider than
     * MatchPolicy.MINIMUM_LEAD (0.003): that constant is "too close to
     * trust at all"; this is "close enough that the embedding alone
     * shouldn't be the only thing deciding which product is even offered
     * as the top candidate." Outside this band, the embedding's own lead
     * is treated as decisive and colour is never looked at -- so this can
     * only ever affect an already-ambiguous case, never a confident one.
     */
    const val NEAR_TIE_BAND = .03f

    /**
     * How much more of the query's colour the runner-up needs to share,
     * versus the current best, before it is worth overriding the
     * embedding's own ranking. A colour comparison this close is "not
     * clear enough to override" rather than resolved by whichever side
     * happens to be marginally ahead -- the same reasoning
     * MatchPolicy.MINIMUM_LEAD applies to the embedding score itself.
     */
    const val MINIMUM_COLOR_LEAD = .05f

    /**
     * Whether two embedding scores are close enough that colour is worth
     * consulting -- see [NEAR_TIE_BAND]. Compares the absolute gap, not
     * just best - runnerUp: rank() always orders its two inputs so that
     * never goes negative in practice, but this function's own contract
     * shouldn't silently assume a caller always gets that order right --
     * two scores a hair apart in either direction are equally "too close
     * to call" from embedding score alone.
     */
    fun isNearTie(bestScore: Float, runnerUpScore: Float): Boolean =
        kotlin.math.abs(bestScore - runnerUpScore) <= NEAR_TIE_BAND

    /**
     * Whether the runner-up should be preferred over the current best,
     * purely on colour -- call only once [isNearTie] says the embedding
     * scores are close enough that this is worth asking at all.
     */
    fun preferRunnerUpByColor(queryColor: FloatArray, bestColor: FloatArray, runnerUpColor: FloatArray): Boolean {
        val bestSimilarity = similarity(queryColor, bestColor)
        val runnerUpSimilarity = similarity(queryColor, runnerUpColor)
        return runnerUpSimilarity - bestSimilarity >= MINIMUM_COLOR_LEAD
    }
}
