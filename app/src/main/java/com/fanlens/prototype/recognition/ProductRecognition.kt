package com.fanlens.prototype.recognition

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.fanlens.prototype.data.CatalogRepository
import com.fanlens.prototype.model.MatchSource
import com.fanlens.prototype.model.NormalizedRect
import com.fanlens.prototype.model.PhotoRole
import com.fanlens.prototype.model.Product
import com.fanlens.prototype.model.ProductDetection
import com.fanlens.prototype.model.RecognitionResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import kotlin.math.roundToInt

/** What the app is doing before it can recognise anything, for the header text. */
data class PreparationState(
    val ready: Boolean = false,
    val failed: Boolean = false,
    val message: String = "Preparing local products…",
    val done: Int = 0,
    val total: Int = 0
)

interface ProductRecognitionEngine : Closeable {
    val preparation: StateFlow<PreparationState>
    suspend fun prepare()

    /** Re-reads the catalogue after products or photos change. */
    suspend fun refresh()
    fun recognize(bitmap: Bitmap): RecognitionResult
}

/**
 * Recognition against the local catalogue.
 *
 * Fingerprints are read from the database rather than recalculated: photos are
 * embedded once, when they are saved or imported, so launching the app is a
 * database read instead of a full pass over every reference photo.
 */
class OnDeviceProductRecognitionEngine(
    private val context: Context,
    private val repository: CatalogRepository
) : ProductRecognitionEngine {

    private val _preparation = MutableStateFlow(PreparationState())
    override val preparation: StateFlow<PreparationState> = _preparation.asStateFlow()

    private var generator: EmbeddingGenerator? = null

    /** Shop photos: a real product in front of the camera. */
    @Volatile
    private var shopIndex: EmbeddingIndex = EmbeddingIndex.EMPTY

    /** Catalogue photos: an image on a laptop or phone screen. */
    @Volatile
    private var catalogueIndex: EmbeddingIndex = EmbeddingIndex.EMPTY

    @Volatile
    private var productsById: Map<String, Product> = emptyMap()

    override suspend fun prepare() {
        if (generator != null) return

        try {
            _preparation.value = PreparationState(message = "Starting local recognition…")
            val created = EmbeddingGenerator.create(context)

            _preparation.value = PreparationState(message = "Adding the shop's products…")
            repository.ensureSeeded { done, total ->
                if (total > 0) {
                    _preparation.value = PreparationState(
                        message = "Adding products — $done of $total",
                        done = done,
                        total = total
                    )
                }
            }

            repository.discardStaleFingerprints(created.modelVersion)
            repository.backfillFingerprints(created) { done, total ->
                _preparation.value = PreparationState(
                    message = "Preparing recognition — $done of $total",
                    done = done,
                    total = total
                )
            }

            generator = created
            loadCatalogue()
        } catch (throwable: Throwable) {
            // The caller turns this into a one-line status; without a log the
            // actual cause is invisible on a real device.
            Log.e(TAG, "Recognition could not start", throwable)
            generator?.close()
            generator = null
            _preparation.value = PreparationState(
                failed = true,
                message = "Recognition could not start"
            )
            throw throwable
        }
    }

    override suspend fun refresh() {
        val active = generator ?: return
        active.let { repository.backfillFingerprints(it) }
        loadCatalogue()
    }

    private suspend fun loadCatalogue() {
        val active = generator ?: return
        shopIndex = repository.loadIndex(active.modelVersion, PhotoRole.Recognition)
        catalogueIndex = repository.loadIndex(active.modelVersion, PhotoRole.Display)
        productsById = repository.products().associateBy(Product::id)

        _preparation.value = PreparationState(
            ready = true,
            message = if (shopIndex.isEmpty && catalogueIndex.isEmpty) {
                "No products saved yet"
            } else {
                "Aim at one product"
            }
        )
    }

    override fun recognize(bitmap: Bitmap): RecognitionResult {
        val activeGenerator = generator
            ?: return RecognitionResult(null, "Preparing local recognition…")

        val shop = shopIndex
        val catalogue = catalogueIndex
        if (shop.isEmpty && catalogue.isEmpty) {
            return RecognitionResult(null, "No products saved yet")
        }

        val query = activeGenerator.embed(bitmap)
            ?: return RecognitionResult(null, "Aim at one product")

        // The actual shop-vs-catalogue decision is pulled out into resolveMatch
        // below so it can be unit tested without a Bitmap or a real model --
        // this just maps that pure decision onto a RecognitionResult.
        val products = productsById
        return when (val decision = resolveMatch(shop, catalogue, query, products::containsKey)) {
            is MatchDecision.Found -> {
                val product = products.getValue(decision.productId)
                RecognitionResult(
                    detection = detection(product, decision.score, decision.source),
                    status = if (decision.source == MatchSource.Shop) {
                        "Match found"
                    } else {
                        "Matched from a catalogue image"
                    }
                )
            }
            is MatchDecision.Closest -> {
                val product = products.getValue(decision.productId)
                RecognitionResult(
                    null,
                    "Closest: ${product.name} ${(decision.score.coerceIn(0f, 1f) * 100).roundToInt()}%"
                )
            }
            MatchDecision.None -> RecognitionResult(null, "Aim at one product")
        }
    }

    private fun detection(product: Product, score: Float, source: MatchSource) = ProductDetection(
        product = product,
        bounds = NormalizedRect(.12f, .20f, .88f, .78f),
        confidence = score.coerceIn(0f, 1f),
        source = source
    )

    override fun close() {
        generator?.close()
        generator = null
        shopIndex = EmbeddingIndex.EMPTY
        catalogueIndex = EmbeddingIndex.EMPTY
        productsById = emptyMap()
    }
}

private const val TAG = "EeRecognition"

/**
 * The outcome of judging one query fingerprint against both indexes -- exactly
 * what [OnDeviceProductRecognitionEngine.recognize] used to compute inline,
 * pulled out so [resolveMatch] can be unit tested with plain [EmbeddingIndex]
 * fixtures instead of a Bitmap and a real on-device model.
 */
internal sealed interface MatchDecision {
    data class Found(val productId: String, val score: Float, val source: MatchSource) : MatchDecision
    data class Closest(val productId: String, val score: Float) : MatchDecision
    data object None : MatchDecision
}

/**
 * Ranks [query] against both indexes and applies [MatchPolicy], shop first:
 * shop is judged and, if accepted, returned immediately -- catalogue is only
 * ever consulted once shop has declined, so adding catalogue photos to the
 * reference set cannot change what happens in front of a real shelf. This is
 * the same decision [OnDeviceProductRecognitionEngine.recognize] made inline
 * before it was extracted here for testability; behaviour is unchanged.
 *
 * [knownProduct] mirrors the caller's live-product lookup, so a fingerprint
 * left behind for a product that no longer exists is skipped rather than
 * reported -- the same guard the inline version had via a nullable map lookup.
 */
internal fun resolveMatch(
    shop: EmbeddingIndex,
    catalogue: EmbeddingIndex,
    query: FloatArray,
    knownProduct: (String) -> Boolean
): MatchDecision {
    val shopRanked = shop.rank(query)
    val shopBest = shopRanked.firstOrNull()
    if (shopBest != null && knownProduct(shopBest.productId)) {
        val runnerUp = shopRanked.getOrNull(1)?.score ?: 0f
        if (MatchPolicy.acceptShop(shopBest.score, runnerUp)) {
            return MatchDecision.Found(shopBest.productId, shopBest.score, MatchSource.Shop)
        }
    }

    val catalogueRanked = catalogue.rank(query)
    val catalogueBest = catalogueRanked.firstOrNull()
    if (catalogueBest != null && knownProduct(catalogueBest.productId)) {
        val runnerUp = catalogueRanked.getOrNull(1)?.score ?: 0f
        if (MatchPolicy.acceptCatalogue(catalogueBest.score, runnerUp)) {
            return MatchDecision.Found(catalogueBest.productId, catalogueBest.score, MatchSource.Catalogue)
        }
    }

    // Report whichever index came closest, so the owner can see why nothing
    // matched -- but only when that guess is close enough to be worth showing,
    // and only when it still resolves to a real product (same as above).
    val closest = listOfNotNull(shopBest, catalogueBest).maxByOrNull { it.score }
    return if (closest != null && knownProduct(closest.productId) && MatchPolicy.shouldShowClosest(closest.score)) {
        MatchDecision.Closest(closest.productId, closest.score)
    } else {
        MatchDecision.None
    }
}

internal object MatchPolicy {
    /**
     * Shop photos against a real product on the shelf.
     *
     * Raised from .48 after measuring the real catalogue: at .48 the aligned
     * pipeline returned 16 right and 6 wrong, at .56 it returns 15 right and 4
     * wrong. Showing a customer the wrong fan is worse than showing nothing.
     */
    const val SHOP_MINIMUM_SIMILARITY = .56f

    /**
     * Catalogue photos against an image on a laptop or phone screen.
     *
     * Measured separately, because the task is different: a screen shows the
     * same picture the catalogue holds, so a true match scores far higher than
     * any shelf photo does. On the real catalogue, correct matches scored no
     * lower than .750 while the best wrong product reached .712 — a genuine gap.
     * .74 sits inside it, accepting every correct match with no wrong match
     * above the line.
     *
     * Deliberately stricter than the shop threshold, and applied only after the
     * shop index has already declined, so shelf accuracy is untouched.
     */
    const val CATALOGUE_MINIMUM_SIMILARITY = .74f

    private const val MINIMUM_LEAD = .003f

    /**
     * The "Closest: <product> <percent>" line shown while scanning finds
     * nothing acceptable is only useful above this. Below it, the guess is
     * little better than random and reads as a match to someone glancing at
     * the screen -- better to say nothing was found. Tune this one constant
     * to raise or lower how confident a near-miss must be before it is shown
     * at all; it has no effect on acceptShop/acceptCatalogue above.
     */
    const val CLOSEST_DISPLAY_THRESHOLD = .50f

    fun acceptShop(best: Float, runnerUp: Float): Boolean =
        best >= SHOP_MINIMUM_SIMILARITY && best - runnerUp >= MINIMUM_LEAD

    fun acceptCatalogue(best: Float, runnerUp: Float): Boolean =
        best >= CATALOGUE_MINIMUM_SIMILARITY && best - runnerUp >= MINIMUM_LEAD

    fun shouldShowClosest(score: Float): Boolean = score >= CLOSEST_DISPLAY_THRESHOLD
}
