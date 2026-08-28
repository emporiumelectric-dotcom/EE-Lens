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
import java.util.UUID

/**
 * Pushes and pulls the whole catalogue against Supabase, so the PC and the
 * phone both read and write ee_lens.products / ee_lens.product_photos
 * directly instead of passing a .eelens file by hand. Mirrors
 * pc-catalogue-manager/cloud.js field-for-field; see that file's header for
 * why the mapping looks the way it does (bigint identity ids vs local UUIDs,
 * paise vs decimal rupees, and so on).
 *
 * Automatic, not a button someone has to remember to press: saving or
 * deleting a product pushes it right away (CatalogRepository's
 * cloudBackgroundPush, via pushOne below), and swiping down to refresh the
 * Products list pulls in the cloud's latest (pullAll) -- what a manual
 * "Pull from cloud" button used to trigger. Not realtime otherwise: there is
 * no open connection or push notification, only these two triggers. .eelens
 * export/import remains the full-fidelity, no-internet-required backup; this
 * is a lighter cloud mirror on top of it, so slug/mrp/currency/source stay
 * local-only.
 *
 * Two devices editing the same product before either syncs is resolved
 * last-write-wins, no merge, no prompt: pullAll only ever takes a remote row
 * that is newer than the local one, and pushProduct (see
 * pushWouldLoseToRemote) refuses to push over a remote row that is already
 * newer than the local edit.
 *
 * Not every local id is a UUID: the bundled demo catalogue
 * (BundledProductCatalog) seeds fixed slugs like "havells-enticer-vineer",
 * which the uuid-typed client_id column rejects outright ("invalid input
 * syntax for type uuid"). For a row like that, a UUID is generated once,
 * sent as client_id, and written back onto the local row as cloudClientId --
 * so every later push and pull agrees on the same cloud identity instead of
 * minting a new one (and a new duplicate remote row) every time.
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
     * Fire-and-forget-friendly single product push, for right after a local
     * save or delete -- mirrors pc-catalogue-manager/cloud.js's
     * cloudBackgroundPush. Silently does nothing and returns false when
     * signed out, so a caller can tell "nothing to record" apart from "a
     * push was attempted" (see pushWouldLoseToRemote for what "attempted"
     * can still mean: last-write-wins may skip the actual upsert).
     */
    suspend fun pushOne(product: ProductEntity): Boolean {
        val session = authClient.ensureFreshSession() ?: return false
        pushProduct(session.accessToken, product)
        return true
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
        // Fetched once, then kept current as rows are written below -- not
        // just for one query instead of several per remote row, but so two
        // remote rows in the same pull that both fall back to a content
        // match (see selectPullMatch) don't both land on the same stale
        // local candidate.
        val localsByCurrentId = database.productDao().allIncludingDeleted()
            .associateByTo(mutableMapOf()) { it.id }
        var processed = 0
        var failed = 0
        val total = remoteProducts.length()
        for (i in 0 until total) {
            onProgress(i, total)
            val row = remoteProducts.getJSONObject(i)
            val clientId = row.optString("client_id").takeIf { it.isNotBlank() } ?: continue
            try {
                pullProduct(token, row, clientId, localsByCurrentId)
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
        val clientId = resolveProductClientId(product)

        // Last-write-wins, made explicit: never overwrite a remote edit made
        // after this one. If the cloud row is already at least as new, this
        // device's copy lost the race -- skip it silently; the next pull
        // brings the winning version back here instead of a merge or a
        // prompt. See pushWouldLoseToRemote (PC's equivalent) and
        // PushConflictPolicyTest.
        val remoteUpdatedAt = sync.fetchRemoteUpdatedAt(accessToken, clientId)
        if (pushWouldLoseToRemote(remoteUpdatedAt?.let(::fromIso), product.updatedAt)) return

        val body = productToRemoteJson(product, clientId)
        val saved = sync.upsertProduct(accessToken, body)
        if (product.deletedAt != null) return // a deleted product has nothing else worth syncing

        val remoteId = saved.getLong("id")
        val photos = database.photoDao().forProduct(product.id)
        val remotePhotoIdByLocalId = mutableMapOf<String, Long>()
        for (photo in photos) {
            val photoClientId = resolvePhotoClientId(photo)
            if (photo.syncedAt == null) {
                val bytes = photoStore.fullFile(product.id, photo.id).readBytes()
                sync.uploadPhoto(accessToken, product.id, photo.id, bytes)
            }
            val photoBody = JSONObject()
                .put("client_id", photoClientId)
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

    /** Resolves this product's client_id, persisting a freshly generated one so it is stable next time. */
    private suspend fun resolveProductClientId(product: ProductEntity): String {
        if (isValidUuid(product.id)) return product.id
        product.cloudClientId?.takeIf { isValidUuid(it) }?.let { return it }
        val generated = UUID.randomUUID().toString()
        database.productDao().setCloudClientId(product.id, generated)
        return generated
    }

    /**
     * Resolves this photo's client_id, persisting a freshly generated one
     * immediately (not batched) so a push that fails partway through a
     * product's photos does not mint a different id -- and a duplicate
     * remote row -- for the same photo on retry.
     */
    private suspend fun resolvePhotoClientId(photo: PhotoEntity): String {
        if (isValidUuid(photo.id)) return photo.id
        photo.cloudClientId?.takeIf { isValidUuid(it) }?.let { return it }
        val generated = UUID.randomUUID().toString()
        database.photoDao().setCloudClientId(photo.id, generated)
        return generated
    }

    private fun productToRemoteJson(product: ProductEntity, clientId: String): JSONObject = JSONObject().apply {
        put("client_id", clientId)
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

    private suspend fun pullProduct(
        token: String?,
        row: JSONObject,
        clientId: String,
        localsByCurrentId: MutableMap<String, ProductEntity>
    ) {
        val local = selectPullMatch(
            clientId = clientId,
            brand = row.optString("brand"),
            name = row.optString("name"),
            model = row.optString("model"),
            locals = localsByCurrentId.values
        )
        val localId = local?.id ?: clientId

        if (!row.isNull("deleted_at") && row.optString("deleted_at").isNotBlank()) {
            if (local != null && local.deletedAt == null) {
                // Record a mapping discovered only just now (the content
                // fallback) even here -- otherwise a later push of this same
                // now-deleted local row mints a second, orphaned deleted row
                // in the cloud instead of updating the one already there.
                if (clientId != localId && local.cloudClientId != clientId) {
                    database.productDao().setCloudClientId(localId, clientId)
                }
                val deletedAt = fromIso(row.optString("deleted_at"))
                database.productDao().softDelete(localId, deletedAt)
                localsByCurrentId[localId] = local.copy(
                    cloudClientId = clientId.takeIf { it != localId } ?: local.cloudClientId,
                    deletedAt = deletedAt,
                    updatedAt = deletedAt
                )
            }
            return
        }

        val remoteUpdatedAt = fromIso(row.optString("updated_at"))
        if (local != null && local.updatedAt >= remoteUpdatedAt) return // local wins; it will be pushed instead

        val remoteId = row.getLong("id")
        val remotePhotos = sync.fetchPhotosForProduct(token, remoteId)
        // Same idea for photos: a legacy-id photo's client_id (its
        // cloudClientId) does not equal its local id, so map through both to
        // find what is already here -- and to resolve the cover photo below.
        val localPhotoIdByCloudId = mutableMapOf<String, String>()
        database.photoDao().forProduct(localId).forEach { p ->
            localPhotoIdByCloudId[p.id] = p.id
            p.cloudClientId?.let { localPhotoIdByCloudId[it] = p.id }
        }

        var coverPhotoId = local?.coverPhotoId
        val now = System.currentTimeMillis()
        val newPhotos = mutableListOf<PhotoEntity>()
        val writtenFiles = mutableListOf<String>()
        try {
            for (j in 0 until remotePhotos.length()) {
                val prow = remotePhotos.getJSONObject(j)
                val photoClientId = prow.optString("client_id").takeIf { it.isNotBlank() } ?: continue
                val remotePhotoId = prow.getLong("id")

                if (!localPhotoIdByCloudId.containsKey(photoClientId)) {
                    val bytes = sync.downloadPhoto(prow.getString("storage_path"))
                    val stored = photoStore.writeRaw(localId, photoClientId, bytes)
                    writtenFiles += photoClientId
                    newPhotos += PhotoEntity(
                        id = photoClientId,
                        productId = localId,
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
                    // A brand new local photo adopts the cloud's id directly --
                    // map it to itself so a cover-photo match below still resolves.
                    localPhotoIdByCloudId[photoClientId] = photoClientId
                }
                if (!row.isNull("cover_photo_id") && row.optLong("cover_photo_id") == remotePhotoId) {
                    coverPhotoId = localPhotoIdByCloudId[photoClientId]
                }
            }

            val entity = ProductEntity(
                id = localId,
                // Whatever path found this row, this device now knows its
                // cloud identity: null when the local id already is that
                // identity (the common case, and a brand new local row),
                // otherwise the client_id itself -- including a match found
                // only by content just now, so the next pull (or push) uses
                // it directly instead of falling back again.
                cloudClientId = clientId.takeIf { it != localId },
                slug = local?.slug?.takeIf { it.isNotBlank() }
                    ?: CatalogRepository.slugify(row.optString("brand"), row.optString("name"), localId),
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
            localsByCurrentId[localId] = entity
        } catch (error: Throwable) {
            writtenFiles.forEach { photoStore.delete(localId, it) }
            throw error
        }
    }

    companion object {
        private val UUID_REGEX = Regex(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
        )

        private fun isValidUuid(value: String): Boolean = UUID_REGEX.matches(value)

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

/**
 * Last-write-wins, made explicit: true when a remote row already carries an
 * updated_at at or after this device's local one, meaning a push would
 * clobber a newer edit made elsewhere with a stale one from here. No merge,
 * no prompt -- the loser's push is simply skipped; its own next pull brings
 * the winning version back to it instead. Pure and dependency-free, mirrors
 * pc-catalogue-manager/cloud.js's pushWouldLoseToRemote; see
 * PushConflictPolicyTest.
 */
internal fun pushWouldLoseToRemote(remoteUpdatedAt: Long?, localUpdatedAt: Long): Boolean {
    if (remoteUpdatedAt == null) return false // never pushed before -- nothing to lose to
    return remoteUpdatedAt >= localUpdatedAt
}

/**
 * Which local product (if any) a cloud row with this identity corresponds
 * to -- the decision that determines whether a pull updates an existing
 * product or duplicates it. Pure and dependency-free (no database, no
 * network) so this exact logic is unit-testable on its own; see
 * CloudSyncManagerTest.
 *
 * Tried in order:
 *  1. The row's client_id as a local id -- the common case: this device's
 *     own earlier push, where the local id already is the cloud identity.
 *  2. As a recorded cloudClientId -- a legacy-id product (client_id isn't a
 *     valid local id there) this device has already reconciled once.
 *  3. By content, live rows only -- a legacy-id product (typically the
 *     bundled catalogue) pushed by a *different* device under a client_id
 *     this one has never seen, so neither id check can find it. Weak
 *     identity on its own, so it only runs once both id checks have missed,
 *     and never matches a product the owner deleted here.
 */
internal fun selectPullMatch(
    clientId: String,
    brand: String,
    name: String,
    model: String,
    locals: Collection<ProductEntity>
): ProductEntity? {
    locals.firstOrNull { it.id == clientId }?.let { return it }
    locals.firstOrNull { it.cloudClientId == clientId }?.let { return it }
    if (brand.isBlank() || name.isBlank()) return null
    return locals.firstOrNull {
        it.deletedAt == null &&
            it.brand.equals(brand, ignoreCase = true) &&
            it.name.equals(name, ignoreCase = true) &&
            it.model.equals(model, ignoreCase = true)
    }
}
