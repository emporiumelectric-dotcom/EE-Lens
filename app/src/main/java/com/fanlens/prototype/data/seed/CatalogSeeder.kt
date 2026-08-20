package com.fanlens.prototype.data.seed

import android.content.Context
import com.fanlens.prototype.data.PhotoStore
import com.fanlens.prototype.data.SeedPolicy
import com.fanlens.prototype.data.SpecsCodec
import com.fanlens.prototype.data.db.EeDatabase
import com.fanlens.prototype.data.db.entity.PhotoEntity
import com.fanlens.prototype.data.db.entity.ProductEntity
import com.fanlens.prototype.model.PhotoOrigin
import com.fanlens.prototype.model.PhotoRole
import com.fanlens.prototype.recognition.BundledProductCatalog
import com.fanlens.prototype.recognition.CatalogEntry
import java.util.UUID

/**
 * Copies the products shipped in the APK into the editable local catalogue,
 * exactly once.
 *
 * Interrupting this is safe: the seed marker is only written after every product
 * has landed, and products already present are skipped, so the next launch picks
 * up where this one stopped.
 */
class CatalogSeeder(
    context: Context,
    private val database: EeDatabase,
    private val photoStore: PhotoStore
) {

    private val assets = context.applicationContext.assets

    suspend fun seedIfNeeded(onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): Int {
        val metaDao = database.metaDao()
        val productDao = database.productDao()

        if (!SeedPolicy.shouldRun(metaDao.intValue(EeDatabase.KEY_SEED_VERSION))) return 0

        val known = productDao.allIdsIncludingDeleted().toSet()
        val pending = SeedPolicy.idsToSeed(BundledProductCatalog.productIds, known)

        pending.forEachIndexed { index, id ->
            BundledProductCatalog.entryById(id)?.let { seedOne(it) }
            onProgress(index + 1, pending.size)
        }

        metaDao.put(EeDatabase.KEY_SEED_VERSION, SeedPolicy.SEED_VERSION.toString())
        metaDao.put(EeDatabase.KEY_SCHEMA_VERSION, SCHEMA_VERSION.toString())
        return pending.size
    }

    private suspend fun seedOne(entry: CatalogEntry) {
        val now = System.currentTimeMillis()
        val product = entry.product

        database.productDao().insert(
            ProductEntity(
                id = product.id,
                slug = product.slug.ifBlank { product.id },
                brand = product.brand,
                name = product.name,
                model = product.model,
                category = product.category,
                colour = product.colour,
                sizeSweepMm = product.sizeSweepMm,
                priceMinor = product.priceMinor,
                mrpMinor = product.mrpMinor,
                currency = product.currency,
                description = product.description,
                specsJson = SpecsCodec.encode(product.specs),
                coverPhotoId = null,
                source = product.source.storageValue(),
                createdAt = now,
                updatedAt = now,
                deletedAt = null
            )
        )

        var coverPhotoId: String? = null
        entry.referenceAssets.forEachIndexed { index, assetPath ->
            val photoId = UUID.randomUUID().toString()
            val stored = runCatching {
                photoStore.store(product.id, photoId) { assets.open(assetPath) }
            }.getOrNull() ?: return@forEachIndexed

            database.photoDao().insert(
                PhotoEntity(
                    id = photoId,
                    productId = product.id,
                    fileName = stored.fileName,
                    sha256 = stored.sha256,
                    width = stored.width,
                    height = stored.height,
                    bytes = stored.bytes,
                    sortOrder = index,
                    origin = PhotoOrigin.Bundled.storageValue(),
                    role = PhotoRole.defaultFor(PhotoOrigin.Bundled).storageValue(),
                    createdAt = now
                )
            )
            if (coverPhotoId == null) coverPhotoId = photoId
        }

        coverPhotoId?.let { cover ->
            database.productDao().byId(product.id)?.let { stored ->
                database.productDao().update(stored.copy(coverPhotoId = cover))
            }
        }
    }

    private companion object {
        const val SCHEMA_VERSION = 1
    }
}
