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

        // Shop photos are asked first and judged exactly as before. Only when
        // they decline does the catalogue index get a say, so adding catalogue
        // photos can never change what happens in front of a real shelf.
        val shopRanked = shop.rank(query)
        val shopBest = shopRanked.firstOrNull()
        if (shopBest != null) {
            val runnerUp = shopRanked.getOrNull(1)?.score ?: 0f
            val product = productsById[shopBest.productId]
            if (product != null && MatchPolicy.acceptShop(shopBest.score, runnerUp)) {
                return RecognitionResult(
                    detection = detection(product, shopBest.score, MatchSource.Shop),
                    status = "Match found"
                )
            }
        }

        val catalogueRanked = catalogue.rank(query)
        val catalogueBest = catalogueRanked.firstOrNull()
        if (catalogueBest != null) {
            val runnerUp = catalogueRanked.getOrNull(1)?.score ?: 0f
            val product = productsById[catalogueBest.productId]
            if (product != null && MatchPolicy.acceptCatalogue(catalogueBest.score, runnerUp)) {
                return RecognitionResult(
                    detection = detection(product, catalogueBest.score, MatchSource.Catalogue),
                    status = "Matched from a catalogue image"
                )
            }
        }

        // Report whichever index came closest, so the owner can see why nothing
        // matched -- but only when that guess is close enough to be worth
        // showing. Below MatchPolicy.CLOSEST_DISPLAY_THRESHOLD it reads as a
        // find when it is really noise (e.g. "Atomberg Aris Contour Smart
        // 14%" for a product that isn't even in frame).
        val closest = listOfNotNull(shopBest, catalogueBest).maxByOrNull { it.score }
        val closestProduct = closest?.let { productsById[it.productId] }
        if (closestProduct == null || !MatchPolicy.shouldShowClosest(closest.score)) {
            return RecognitionResult(null, "Aim at one product")
        }

        return RecognitionResult(
            null,
            "Closest: ${closestProduct.name} ${(closest.score.coerceIn(0f, 1f) * 100).roundToInt()}%"
        )
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
