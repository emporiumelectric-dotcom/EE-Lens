package com.fanlens.prototype.supabase

import androidx.room.withTransaction
import com.fanlens.prototype.data.CatalogRepository
import com.fanlens.prototype.data.PhotoStore
import com.fanlens.prototype.data.db.EeDatabase
import com.fanlens.prototype.data.db.entity.PhotoEntity
import com.fanlens.prototype.data.db.entity.ProductEntity
import com.fanlens.prototype.model.PhotoOrigin
import com.fanlens.prototype.model.PhotoRole
import com.fanlens.prototype.model.ProductSource
import org.json.JSONObject
import java.math.BigDecimal
import java.time.Instant

/**
 * Pushes and pulls the whole catalogue against Supabase, so the PC and the
 * phone both read and write ee_lens.products / ee_lens.product_photos
 * directly instead of passing a .eelens file by hand. Mirrors
 * pc-catalogue-manager/cloud.js field-for-field; see that file's header for
 * why the mapping looks the way it does (bigint identity ids vs local UUIDs,
 * paise vs decimal rupees, and so on).
 *
 * A manual, pull-based sync -- not realtime -- matching this screen's
 * existing "Sync with the PC" pattern. .eelens export/import remains the
 * full-fidelity, no-internet-required backup; this is a lighter cloud
 * mirror on top of it, so slug/mrp/currency/source stay local-only.
 */
class CloudSyncManager(
    private val database: EeDatabase,
    private val photoStore: PhotoStore,
    private val authClient: SupabaseAuthClient,
    private val sync: SupabaseSyncClient = SupabaseSyncClient()
) {

    data class SyncSummary(val processed: Int, val failed: Int, val total: Int)

    suspend fun pushAll(onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): SyncSummary {
        val session = authClient.ensureFreshSession()
            ?: throw SupabaseAuthException("Sign in to push to the cloud.")
        val all = database.productDao().allIncludingDeleted()
        var processed = 0
        var failed = 0
        all.forEachIndexed { index, product ->
            onProgress(index, all.size)
            try {
                pushProduct(session.accessToken, product)
                processed++
            } catch (error: Throwable) {
                failed++
            }
        }
        onProgress(all.size, all.size)
        return SyncSummary(processed, failed, all.size)
    }

    /**
     * Reading is open to anon, so this works even signed out. A remote row
     * newer than the matching local one (or unseen locally) overwrites it; a
     * locally-newer row is left alone and wins on the next push instead. A
     * remote deletion soft-deletes the local product.
     */
    suspend fun pullAll(onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): SyncSummary {
        val token = authClient.ensureFreshSession()?.accessToken
        val remoteProducts = sync.fetchAllProducts(token)
        var processed = 0
        var failed = 0
        val total = remoteProducts.length()
        for (i in 0 until total) {
            onProgress(i, total)
            val row = remoteProducts.getJSONObject(i)
            val clientId = row.optString("client_id").takeIf { it.isNotBlank() } ?: continue
            try {
                pullProduct(token, row, clientId)
                processed++
            } catch (error: Throwable) {
                failed++
            }
        }
        onProgress(total, total)
        return SyncSummary(processed, failed, total)
    }

    // ---------------- push ----------------

    private suspend fun pushProduct(accessToken: String, product: ProductEntity) {
        val body = productToRemoteJson(product)
        val saved = sync.upsertProduct(accessToken, body)
        if (product.deletedAt != null) return // a deleted product has nothing else worth syncing

        val remoteId = saved.getLong("id")
        val photos = database.photoDao().forProduct(product.id)
        val remotePhotoIdByLocalId = mutableMapOf<String, Long>()
        for (photo in photos) {
            if (photo.syncedAt == null) {
                val bytes = photoStore.fullFile(product.id, photo.id).readBytes()
                sync.uploadPhoto(accessToken, product.id, photo.id, bytes)
            }
            val photoBody = JSONObject()
                .put("client_id", photo.id)
                .put("product_id", remoteId)
                .put("role", localRoleToCloud(photo.role))
                .put("storage_path", SupabaseSyncClient.photoStoragePath(product.id, photo.id))
                .put("sort_order", photo.sortOrder)
                .put("checksum", photo.sha256)
            val savedPhoto = sync.upsertPhoto(accessToken, photoBody)
            remotePhotoIdByLocalId[photo.id] = savedPhoto.getLong("id")
            if (photo.syncedAt == null) database.photoDao().setSyncedAt(photo.id, System.currentTimeMillis())
        }

        val coverRemoteId = product.coverPhotoId?.let { remotePhotoIdByLocalId[it] }
        val currentRemoteCover = if (saved.isNull("cover_photo_id")) null else saved.getLong("cover_photo_id")
        if (coverRemoteId != currentRemoteCover) {
            sync.upsertProduct(accessToken, body.put("cover_photo_id", coverRemoteId ?: JSONObject.NULL))
        }
    }

    private fun productToRemoteJson(product: ProductEntity): JSONObject = JSONObject().apply {
        put("client_id", product.id)
        put("brand", product.brand)
        put("name", product.name)
        put("model", if (product.model.isBlank()) JSONObject.NULL else product.model)
        put("category", product.category?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
        put("colour", product.colour?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
        put("size", sizeMmToRemote(product.sizeSweepMm))
        put("price", priceMinorToRemote(product.priceMinor))
        put("description", if (product.description.isBlank()) JSONObject.NULL else product.description)
        put("specs", JSONObject(product.specsJson.ifBlank { "{}" }))
        put("created_at", toIso(product.createdAt))
        put("updated_at", toIso(product.updatedAt))
        put("deleted_at", product.deletedAt?.let(::toIso) ?: JSONObject.NULL)
    }

    // ---------------- pull ----------------

    private suspend fun pullProduct(token: String?, row: JSONObject, clientId: String) {
        val local = database.productDao().byId(clientId)

        if (!row.isNull("deleted_at") && row.optString("deleted_at").isNotBlank()) {
            if (local != null && local.deletedAt == null) {
                database.productDao().softDelete(clientId, fromIso(row.optString("deleted_at")))
            }
            return
        }

        val remoteUpdatedAt = fromIso(row.optString("updated_at"))
        if (local != null && local.updatedAt >= remoteUpdatedAt) return // local wins; it will be pushed instead

        val remoteId = row.getLong("id")
        val remotePhotos = sync.fetchPhotosForProduct(token, remoteId)
        val existingPhotoIds = database.photoDao().forProduct(clientId).map { it.id }.toSet()

        var coverPhotoId = local?.coverPhotoId
        val now = System.currentTimeMillis()
        val newPhotos = mutableListOf<PhotoEntity>()
        val writtenFiles = mutableListOf<String>()
        try {
            for (j in 0 until remotePhotos.length()) {
                val prow = remotePhotos.getJSONObject(j)
                val photoClientId = prow.optString("client_id").takeIf { it.isNotBlank() } ?: continue
                val remotePhotoId = prow.getLong("id")

                if (!existingPhotoIds.contains(photoClientId)) {
                    val bytes = sync.downloadPhoto(clientId, photoClientId)
                    val stored = photoStore.writeRaw(clientId, photoClientId, bytes)
                    writtenFiles += photoClientId
                    newPhotos += PhotoEntity(
                        id = photoClientId,
                        productId = clientId,
                        fileName = stored.fileName,
                        sha256 = stored.sha256,
                        width = stored.width,
                        height = stored.height,
                        bytes = stored.bytes,
                        sortOrder = prow.optInt("sort_order", 0),
                        origin = PhotoOrigin.Import.storageValue(),
                        role = cloudRoleToLocal(prow.optString("role")),
                        createdAt = now,
                        // Just downloaded from the cloud, so its bytes are already in sync.
                        syncedAt = now
                    )
                }
                if (!row.isNull("cover_photo_id") && row.optLong("cover_photo_id") == remotePhotoId) {
                    coverPhotoId = photoClientId
                }
            }

            val entity = ProductEntity(
                id = clientId,
                slug = local?.slug?.takeIf { it.isNotBlank() }
                    ?: CatalogRepository.slugify(row.optString("brand"), row.optString("name"), clientId),
                brand = row.optString("brand"),
                name = row.optString("name"),
                model = row.optString("model"),
                category = row.optString("category").takeIf { it.isNotBlank() },
                colour = row.optString("colour").takeIf { it.isNotBlank() },
                sizeSweepMm = if (row.isNull("size")) null else parseSizeMm(row.optString("size")),
                priceMinor = if (row.isNull("price")) null else BigDecimal(row.get("price").toString())
                    .movePointRight(2).toLong(),
                mrpMinor = local?.mrpMinor,
                currency = local?.currency ?: "INR",
                description = row.optString("description"),
                specsJson = row.optJSONObject("specs")?.toString() ?: "{}",
                coverPhotoId = coverPhotoId,
                source = local?.source ?: ProductSource.Imported.storageValue(),
                createdAt = fromIso(row.optString("created_at")),
                updatedAt = remoteUpdatedAt,
                deletedAt = null
            )

            database.withTransaction {
                if (local != null) database.productDao().update(entity) else database.productDao().insert(entity)
                newPhotos.forEach { database.photoDao().insert(it) }
            }
        } catch (error: Throwable) {
            writtenFiles.forEach { photoStore.delete(clientId, it) }
            throw error
        }
    }

    companion object {
        private fun localRoleToCloud(role: String): String =
            if (role == PhotoRole.Display.storageValue()) "catalogue" else "shop"

        private fun cloudRoleToLocal(role: String?): String =
            if (role == "catalogue") PhotoRole.Display.storageValue() else PhotoRole.Recognition.storageValue()

        private fun sizeMmToRemote(mm: Int?): Any = mm?.let { "${it}mm" } ?: JSONObject.NULL

        private fun parseSizeMm(size: String): Int? =
            Regex("\\d+").find(size)?.value?.toIntOrNull()

        private fun priceMinorToRemote(minor: Long?): Any =
            minor?.let { BigDecimal(it).movePointLeft(2) } ?: JSONObject.NULL

        private fun toIso(epochMs: Long): String = Instant.ofEpochMilli(epochMs).toString()

        private fun fromIso(iso: String?): Long =
            iso?.takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
                ?: System.currentTimeMillis()
    }
}
