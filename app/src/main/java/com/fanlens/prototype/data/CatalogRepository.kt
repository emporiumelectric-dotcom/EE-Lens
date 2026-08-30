package com.fanlens.prototype.data

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.fanlens.prototype.data.db.EeDatabase
import com.fanlens.prototype.data.db.entity.EmbeddingEntity
import com.fanlens.prototype.data.db.entity.PhotoEntity
import com.fanlens.prototype.data.db.entity.ProductEntity
import com.fanlens.prototype.data.seed.CatalogSeeder
import com.fanlens.prototype.eelens.EelensWriter
import com.fanlens.prototype.eelens.ExportSummary
import com.fanlens.prototype.eelens.ImportConflictPolicy
import com.fanlens.prototype.eelens.ImportSummary
import com.fanlens.prototype.eelens.StagedPackage
import com.fanlens.prototype.model.Photo
import com.fanlens.prototype.model.PhotoOrigin
import com.fanlens.prototype.model.PhotoRole
import com.fanlens.prototype.model.Product
import com.fanlens.prototype.model.ProductDraft
import com.fanlens.prototype.model.ProductSource
import com.fanlens.prototype.model.ProductWithPhotos
import com.fanlens.prototype.recognition.EmbeddingGenerator
import com.fanlens.prototype.recognition.EmbeddingIndex
import com.fanlens.prototype.recognition.IndexedFingerprint
import com.fanlens.prototype.recognition.RecognitionPreprocessing
import com.fanlens.prototype.recognition.VectorCodec
import com.fanlens.prototype.supabase.CloudSyncManager
import com.fanlens.prototype.supabase.SupabaseAuthClient
import com.fanlens.prototype.util.Money
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import java.util.UUID

/** A photo the owner has chosen but not yet saved. Nothing is on disk yet. */
data class PendingPhoto(
    val localId: String,
    val encoded: EncodedImage,
    val origin: PhotoOrigin,
    val role: PhotoRole
)

/** Result of trying to take a photo into a draft. */
sealed interface AddPhotoOutcome {
    data class Added(val photo: PendingPhoto) : AddPhotoOutcome
    data object Duplicate : AddPhotoOutcome
    data class Unreadable(val message: String) : AddPhotoOutcome
}

/**
 * The single place that reads or writes the catalogue.
 *
 * Saves are all-or-nothing: photo files are written first, then every database
 * row lands in one transaction, and a failure removes the files it just wrote.
 * An interrupted save leaves no half-made product behind.
 */
class CatalogRepository(
    context: Context,
    private val database: EeDatabase,
    val photoStore: PhotoStore
) {

    private val seeder = CatalogSeeder(context, database, photoStore)

    /**
     * Shared with every screen that touches cloud sync (the Backup screen's
     * sign-in/out, the Products list's pull-to-refresh, and the background
     * push below), so there is exactly one Supabase session and one sync
     * client per process instead of each screen building its own.
     */
    val cloudAuth = SupabaseAuthClient(database.metaDao())
    private val cloudSync = CloudSyncManager(database, photoStore, cloudAuth)

    // ---------------- reads ----------------

    fun observeProducts(): Flow<List<Product>> =
        database.productDao().observeLive().map { rows -> rows.map(ProductEntity::toProduct) }

    suspend fun products(): List<Product> =
        database.productDao().liveProducts().map(ProductEntity::toProduct)

    suspend fun productCount(): Int = database.productDao().liveCount()

    suspend fun productWithPhotos(productId: String): ProductWithPhotos? {
        val product = database.productDao().byId(productId)?.takeIf { it.deletedAt == null } ?: return null
        val photos = database.photoDao().forProduct(productId).map(PhotoEntity::toPhoto)
        return ProductWithPhotos(product.toProduct(), photos)
    }

    fun observePhotos(productId: String): Flow<List<Photo>> =
        database.photoDao().observeForProduct(productId).map { rows -> rows.map(PhotoEntity::toPhoto) }

    suspend fun photos(productId: String): List<Photo> =
        database.photoDao().forProduct(productId).map(PhotoEntity::toPhoto)

    fun fileFor(photo: Photo): File = photoStore.fullFile(photo.productId, photo.id)

    fun thumbFor(photo: Photo): File = photoStore.thumbFile(photo.productId, photo.id)

    /**
     * Thumbnail to show for a product in lists: a catalogue photo whenever one
     * exists, a shop photo only as a fallback.
     */
    suspend fun coverThumb(productId: String): File? =
        productWithPhotos(productId)?.coverPhoto?.let(::thumbFor)

    // ---------------- photo staging ----------------

    /**
     * Compresses a chosen image and checks it is not already on this product.
     * Nothing is written to disk until the product is saved.
     */
    suspend fun stagePhoto(
        productId: String?,
        origin: PhotoOrigin,
        alreadyStaged: List<PendingPhoto>,
        open: () -> InputStream
    ): AddPhotoOutcome {
        val encoded = try {
            photoStore.encode(open)
        } catch (error: Throwable) {
            return AddPhotoOutcome.Unreadable(
                error.message ?: "This file could not be read as an image."
            )
        }

        if (alreadyStaged.any { it.encoded.sha256 == encoded.sha256 }) return AddPhotoOutcome.Duplicate
        if (productId != null &&
            database.photoDao().duplicateCount(productId, encoded.sha256) > 0
        ) {
            return AddPhotoOutcome.Duplicate
        }

        return AddPhotoOutcome.Added(
            PendingPhoto(
                localId = UUID.randomUUID().toString(),
                encoded = encoded,
                origin = origin,
                role = PhotoRole.defaultFor(origin)
            )
        )
    }

    /**
     * Moves a saved photo between recognition and display. Dropping a photo out
     * of the recognition set discards its fingerprint; adding one back leaves it
     * for the next backfill to pick up.
     */
    suspend fun setPhotoRole(photoId: String, role: PhotoRole) {
        database.photoDao().setRole(photoId, role.storageValue())
    }

    // ---------------- writes ----------------

    /**
     * Creates or updates a product together with its photo changes.
     *
     * @param coverSelection either an existing photo id or a staged photo's local
     *   id; falls back to the first surviving photo.
     * @return the product id, which is newly generated for a new product.
     */
    suspend fun saveProduct(
        draft: ProductDraft,
        newPhotos: List<PendingPhoto> = emptyList(),
        removedPhotoIds: Set<String> = emptySet(),
        coverSelection: String? = null,
        roleChanges: Map<String, PhotoRole> = emptyMap()
    ): String {
        val now = System.currentTimeMillis()
        val productId = draft.id ?: UUID.randomUUID().toString()
        val existing = draft.id?.let { database.productDao().byId(it) }

        // Written before the transaction so the database lock is never held
        // across file I/O; removed again if the transaction fails.
        val assignedIds = newPhotos.associate { it.localId to UUID.randomUUID().toString() }
        val written = mutableListOf<Pair<String, StoredImage>>()
        try {
            newPhotos.forEach { pending ->
                val photoId = assignedIds.getValue(pending.localId)
                written += photoId to photoStore.write(productId, photoId, pending.encoded)
            }

            database.withTransaction {
                val startingOrder = if (existing == null) 0 else database.photoDao().nextSortOrder(productId)

                database.productDao().let { dao ->
                    val entity = ProductEntity(
                        id = productId,
                        slug = existing?.slug ?: slugify(draft.brand, draft.name, productId),
                        brand = draft.brand.trim(),
                        name = draft.name.trim(),
                        model = draft.model.trim(),
                        category = draft.category.trim().ifBlank { null },
                        colour = draft.colour.trim().ifBlank { null },
                        sizeSweepMm = draft.sizeSweep.trim().toIntOrNull(),
                        priceMinor = Money.parseToMinor(draft.priceText),
                        mrpMinor = Money.parseToMinor(draft.mrpText),
                        currency = existing?.currency ?: "INR",
                        description = draft.description.trim(),
                        specsJson = SpecsCodec.encode(draft.specsMap()),
                        coverPhotoId = existing?.coverPhotoId,
                        source = existing?.source ?: ProductSource.User.storageValue(),
                        createdAt = existing?.createdAt ?: now,
                        updatedAt = now,
                        deletedAt = null
                    )
                    if (existing == null) dao.insert(entity) else dao.update(entity)
                }

                removedPhotoIds.forEach { database.photoDao().delete(it) }

                // A moved photo keeps its fingerprint; only which index it feeds
                // changes, and that is decided at load time from its role.
                roleChanges.forEach { (photoId, role) ->
                    database.photoDao().setRole(photoId, role.storageValue())
                }

                newPhotos.forEachIndexed { index, pending ->
                    val photoId = assignedIds.getValue(pending.localId)
                    val stored = written.first { it.first == photoId }.second
                    database.photoDao().insert(
                        PhotoEntity(
                            id = photoId,
                            productId = productId,
                            fileName = stored.fileName,
                            sha256 = stored.sha256,
                            width = stored.width,
                            height = stored.height,
                            bytes = stored.bytes,
                            sortOrder = startingOrder + index,
                            origin = pending.origin.storageValue(),
                            role = pending.role.storageValue(),
                            createdAt = now
                        )
                    )
                }

                val surviving = database.photoDao().forProduct(productId)
                val requestedCover = coverSelection?.let { assignedIds[it] ?: it }
                // Customers see the cover, so a clean display photo wins by
                // default; a product with only shop photos still gets one.
                val cover = surviving.firstOrNull { it.id == requestedCover }?.id
                    ?: surviving.firstOrNull { it.role == PhotoRole.Display.storageValue() }?.id
                    ?: surviving.firstOrNull()?.id

                database.productDao().byId(productId)?.let { stored ->
                    database.productDao().update(stored.copy(coverPhotoId = cover))
                }
            }
        } catch (error: Throwable) {
            written.forEach { (photoId, _) -> photoStore.delete(productId, photoId) }
            throw error
        }

        // Files for rows deleted above are only removed once the transaction has
        // committed, so a rollback cannot leave a row pointing at a missing file.
        removedPhotoIds.forEach { photoStore.delete(productId, it) }
        database.productDao().byId(productId)?.let(::cloudBackgroundPush)
        return productId
    }

    /** Hides a product immediately; [purgeDeletedBefore] removes its files later. */
    suspend fun softDeleteProduct(productId: String) {
        database.productDao().softDelete(productId, System.currentTimeMillis())
        database.productDao().byId(productId)?.let(::cloudBackgroundPush)
    }

    suspend fun restoreProduct(productId: String) {
        database.productDao().restore(productId, System.currentTimeMillis())
    }

    /** Permanently removes products deleted before [cutoff], files included. */
    suspend fun purgeDeletedBefore(cutoff: Long): Int {
        val purgeable = database.productDao().purgeable(cutoff)
        purgeable.forEach { product ->
            photoStore.deleteProduct(product.id)
            database.productDao().deleteForever(product.id)
        }
        return purgeable.size
    }

    suspend fun setCoverPhoto(productId: String, photoId: String) {
        database.productDao().byId(productId)?.let { stored ->
            database.productDao().update(
                stored.copy(coverPhotoId = photoId, updatedAt = System.currentTimeMillis())
            )
        }
    }

    // ---------------- seeding and fingerprints ----------------

    suspend fun ensureSeeded(onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): Int =
        seeder.seedIfNeeded(onProgress)

    /**
     * Generates and stores fingerprints for any photo that does not yet have one
     * for this model. Photos saved while the app was closing are picked up here
     * on the next launch, which is why saving does not need to block.
     */
    suspend fun backfillFingerprints(
        generator: EmbeddingGenerator,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): Int {
        val pending = database.photoDao().missingFingerprints(generator.modelVersion)
        if (pending.isEmpty()) return 0

        var written = 0
        pending.forEachIndexed { index, photo ->
            val bitmap = photoStore.loadFull(photo.productId, photo.id)
            if (bitmap != null) {
                // Identical preparation to a live camera frame; see
                // RecognitionPreprocessing for why this matters.
                val prepared = RecognitionPreprocessing.centreSquare(bitmap)
                val vector = generator.embed(prepared)
                if (prepared !== bitmap) prepared.recycle()
                bitmap.recycle()
                if (vector != null) {
                    database.embeddingDao().upsert(
                        EmbeddingEntity(
                            photoId = photo.id,
                            modelId = generator.modelId,
                            modelVersion = generator.modelVersion,
                            dim = vector.size,
                            vector = VectorCodec.encode(vector),
                            createdAt = System.currentTimeMillis()
                        )
                    )
                    written++
                }
            }
            onProgress(index + 1, pending.size)
        }
        return written
    }

    /** Builds the index for one photo role. Shop and catalogue are kept apart. */
    suspend fun loadIndex(modelVersion: String, role: PhotoRole): EmbeddingIndex {
        val rows = database.embeddingDao().fingerprintsForRole(modelVersion, role.storageValue())
        if (rows.isEmpty()) return EmbeddingIndex.EMPTY

        return EmbeddingIndex.build(
            dim = rows.first().dim,
            fingerprints = rows.map { IndexedFingerprint(it.productId, VectorCodec.decode(it.vector)) }
        )
    }

    suspend fun discardStaleFingerprints(modelVersion: String) {
        database.embeddingDao().deleteOtherModels(modelVersion)
        database.metaDao().put(EeDatabase.KEY_MODEL_VERSION, modelVersion)
    }

    suspend fun storageUsedBytes(): Long = photoStore.usedBytes()

    /** The PC address and pairing code, so they are typed once and remembered. */
    suspend fun syncAddress(): String = database.metaDao().value(EeDatabase.KEY_SYNC_ADDRESS).orEmpty()

    suspend fun syncCode(): String = database.metaDao().value(EeDatabase.KEY_SYNC_CODE).orEmpty()

    suspend fun rememberSync(address: String, code: String) {
        database.metaDao().put(EeDatabase.KEY_SYNC_ADDRESS, address.trim())
        database.metaDao().put(EeDatabase.KEY_SYNC_CODE, code.trim())
    }

    // ---------------- portable catalogue ----------------

    /** Writes every live product and photo into [output] as a .eelens package. */
    suspend fun exportTo(
        output: java.io.OutputStream,
        appVersion: String,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): ExportSummary {
        val live = products()
        val photos = live.associate { it.id to photos(it.id) }
        return EelensWriter(photoStore).write(output, live, photos, appVersion, onProgress)
    }

    suspend fun knownProductIds(): Set<String> =
        database.productDao().allIdsIncludingDeleted().toSet()

    /**
     * Commits a package that has already been read and checked.
     *
     * Photo files are written first, then every row lands in one transaction;
     * if that fails, the files written here are removed again. An interrupted
     * import leaves the catalogue exactly as it was.
     */
    suspend fun commitImport(
        staged: StagedPackage,
        policy: ImportConflictPolicy
    ): ImportSummary {
        val known = knownProductIds()
        val now = System.currentTimeMillis()
        var added = 0
        var replaced = 0
        var skipped = 0
        var photosAdded = 0

        val writtenFiles = mutableListOf<Pair<String, String>>()
        try {
            for (entry in staged.products) {
                val exists = entry.product.id in known
                if (exists && policy == ImportConflictPolicy.KeepMine) {
                    skipped++
                    continue
                }

                for (photo in entry.photos) {
                    photoStore.writeRaw(entry.product.id, photo.id, photo.bytes)
                    writtenFiles += entry.product.id to photo.id
                }

                database.withTransaction {
                    if (exists) {
                        // Replace means replace: the old photos go with it.
                        database.photoDao().forProduct(entry.product.id).forEach {
                            database.photoDao().delete(it.id)
                        }
                    }

                    val row = entry.product.toEntity(now)
                    if (exists) database.productDao().update(row) else database.productDao().insert(row)

                    entry.photos.forEachIndexed { index, photo ->
                        database.photoDao().insert(
                            PhotoEntity(
                                id = photo.id,
                                productId = entry.product.id,
                                fileName = "${photo.id}.jpg",
                                sha256 = photo.sha256,
                                width = photo.width,
                                height = photo.height,
                                bytes = photo.bytes.size.toLong(),
                                sortOrder = photo.sortOrder.takeIf { it >= 0 } ?: index,
                                origin = photo.origin.storageValue(),
                                role = photo.role.storageValue(),
                                createdAt = now
                            )
                        )
                        photosAdded++
                    }

                    val cover = entry.product.coverPhotoId
                        ?.takeIf { id -> entry.photos.any { it.id == id } }
                        ?: entry.photos.firstOrNull { it.role == PhotoRole.Display }?.id
                        ?: entry.photos.firstOrNull()?.id
                    database.productDao().byId(entry.product.id)?.let { stored ->
                        database.productDao().update(stored.copy(coverPhotoId = cover))
                    }
                }

                if (exists) replaced++ else added++
            }
        } catch (error: Throwable) {
            writtenFiles.forEach { (productId, photoId) -> photoStore.delete(productId, photoId) }
            throw error
        }

        return ImportSummary(
            productsAdded = added,
            productsReplaced = replaced,
            productsSkipped = skipped,
            photosAdded = photosAdded,
            photosMissing = staged.preview.missingPhotos.size,
            photosCorrupt = staged.preview.corruptPhotos.size
        )
    }

    private fun Product.toEntity(now: Long) = ProductEntity(
        id = id,
        slug = slug.ifBlank { slugify(brand, name, id) },
        brand = brand,
        name = name,
        model = model,
        category = category,
        colour = colour,
        sizeSweepMm = sizeSweepMm,
        priceMinor = priceMinor,
        mrpMinor = mrpMinor,
        currency = currency,
        description = description,
        specsJson = SpecsCodec.encode(specs),
        coverPhotoId = null,
        source = ProductSource.Imported.storageValue(),
        createdAt = createdAt.takeIf { it > 0 } ?: now,
        updatedAt = now,
        deletedAt = null
    )

    // ---------------- cloud sync ----------------

    /** Pushes every local product to the cloud. Needs a signed-in session. */
    suspend fun cloudPushAll(onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): CloudSyncManager.SyncSummary {
        val summary = cloudSync.pushAll(onProgress)
        database.metaDao().put(EeDatabase.KEY_CLOUD_LAST_PUSH_AT, System.currentTimeMillis().toString())
        return summary
    }

    /**
     * Pulls in the cloud's latest -- the same logic a "Pull from cloud"
     * button used to trigger, now driven by swiping down to refresh the
     * Products list. Works even signed out; reading is open to everyone.
     */
    suspend fun cloudPullAll(onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): CloudSyncManager.SyncSummary {
        val summary = cloudSync.pullAll(onProgress)
        database.metaDao().put(EeDatabase.KEY_CLOUD_LAST_PULL_AT, System.currentTimeMillis().toString())
        return summary
    }

    suspend fun cloudLastPushAt(): Long? = database.metaDao().value(EeDatabase.KEY_CLOUD_LAST_PUSH_AT)?.toLongOrNull()

    suspend fun cloudLastPullAt(): Long? = database.metaDao().value(EeDatabase.KEY_CLOUD_LAST_PULL_AT)?.toLongOrNull()

    /**
     * Fire-and-forget push of one product to the cloud, for right after a
     * local save or delete -- mirrors pc-catalogue-manager/app.js's
     * cloudBackgroundPush. Never blocks the caller and never throws; the
     * product is already safely saved locally either way. Silently does
     * nothing when signed out.
     */
    @OptIn(DelicateCoroutinesApi::class)
    private fun cloudBackgroundPush(product: ProductEntity) {
        Log.d(CloudSyncManager.PUSH_TAG, "cloudBackgroundPush queued for localId=${product.id}")
        GlobalScope.launch(Dispatchers.IO) {
            try {
                if (cloudSync.pushOne(product)) {
                    database.metaDao().put(EeDatabase.KEY_CLOUD_LAST_PUSH_AT, System.currentTimeMillis().toString())
                    Log.d(CloudSyncManager.PUSH_TAG, "cloudBackgroundPush SUCCESS for localId=${product.id}; last-pushed-at updated")
                } else {
                    Log.d(CloudSyncManager.PUSH_TAG, "cloudBackgroundPush NO-OP for localId=${product.id}: not signed in")
                }
            } catch (error: Throwable) {
                // pushOne threw somewhere inside pushProduct -- the product
                // is still safely saved locally, but "last pushed" is
                // deliberately NOT updated here: see PUSH_TAG's own log
                // lines above this one (uploading/upserting/deleting a
                // specific photo, or the product row itself) for exactly
                // where it failed, instead of just this one summary line.
                Log.e(TAG, "Cloud push failed for ${product.id}", error)
            }
        }
    }

    companion object {
        private const val TAG = "EeCatalogRepository"

        /** Guidance only; the owner is warned rather than blocked. */
        const val RECOMMENDED_MIN_PHOTOS = 6
        const val RECOMMENDED_MAX_PHOTOS = 10

        fun slugify(brand: String, name: String, fallback: String): String {
            val slug = "$brand $name".lowercase()
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
            return slug.ifBlank { fallback }
        }
    }
}

private fun ProductEntity.toProduct(): Product = Product(
    id = id,
    name = name,
    model = model,
    description = description,
    slug = slug,
    brand = brand,
    category = category,
    colour = colour,
    sizeSweepMm = sizeSweepMm,
    priceMinor = priceMinor,
    mrpMinor = mrpMinor,
    currency = currency,
    specs = SpecsCodec.decode(specsJson),
    coverPhotoId = coverPhotoId,
    source = ProductSource.fromStorage(source),
    createdAt = createdAt,
    updatedAt = updatedAt
)

private fun PhotoEntity.toPhoto(): Photo = Photo(
    id = id,
    productId = productId,
    fileName = fileName,
    sha256 = sha256,
    width = width,
    height = height,
    bytes = bytes,
    sortOrder = sortOrder,
    origin = PhotoOrigin.fromStorage(origin),
    role = PhotoRole.fromStorage(role),
    createdAt = createdAt
)
