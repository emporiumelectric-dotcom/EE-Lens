package com.fanlens.prototype.supabase

import com.fanlens.prototype.data.db.entity.PhotoEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers remotePhotoClientIdsToDelete and photosToRemoveLocally -- the fix
 * for a real bug found investigating "a photo removed on the phone never
 * disappears from the web tool": pushProduct only ever upserted a product's
 * *current* local photos, and pullProduct only ever added a remote photo
 * missing locally. Neither ever told the other side "this one is gone" --
 * so a removed photo's remote row and storage object lived forever, on
 * both platforms, in both directions. The auto-push *wiring* itself
 * (cloudBackgroundPush firing on save/delete) was already correct; this was
 * never a routing bug, it was a missing feature in what a push/pull
 * actually reconciles.
 */
class PhotoSyncRemovalTest {

    // ---------------- remotePhotoClientIdsToDelete (push side) ----------------

    @Test
    fun aRemotePhotoWithNoSurvivingLocalMatchIsMarkedForDeletion() {
        val result = remotePhotoClientIdsToDelete(
            remoteClientIds = listOf("kept-1", "removed-1"),
            survivingClientIds = setOf("kept-1")
        )
        assertEquals(setOf("removed-1"), result)
    }

    @Test
    fun everyRemotePhotoStillSurvivingLocallyDeletesNothing() {
        val result = remotePhotoClientIdsToDelete(
            remoteClientIds = listOf("a", "b", "c"),
            survivingClientIds = setOf("a", "b", "c")
        )
        assertEquals(emptySet<String>(), result)
    }

    @Test
    fun everyLocalPhotoRemovedDeletesEveryRemoteOne() {
        // The exact reported scenario: all photos on this product were
        // removed in one edit, so nothing local survives to compare against.
        val result = remotePhotoClientIdsToDelete(
            remoteClientIds = listOf("a", "b"),
            survivingClientIds = emptySet()
        )
        assertEquals(setOf("a", "b"), result)
    }

    @Test
    fun aBrandNewProductWithNothingRemoteYetDeletesNothing() {
        val result = remotePhotoClientIdsToDelete(
            remoteClientIds = emptyList(),
            survivingClientIds = setOf("just-uploaded")
        )
        assertEquals(emptySet<String>(), result)
    }

    // ---------------- photosToRemoveLocally (pull side) ----------------

    private fun photo(
        id: String,
        cloudClientId: String? = null,
        syncedAt: Long? = 1_000L
    ) = PhotoEntity(
        id = id,
        productId = "product-1",
        fileName = "$id.jpg",
        sha256 = "sha-$id",
        width = 100,
        height = 100,
        bytes = 1_000L,
        sortOrder = 0,
        origin = "file",
        role = "recognition",
        createdAt = 0L,
        syncedAt = syncedAt,
        cloudClientId = cloudClientId
    )

    @Test
    fun aSyncedLocalPhotoMissingRemotelyIsRemoved() {
        val result = photosToRemoveLocally(
            localPhotos = listOf(photo(id = "gone")),
            remoteClientIds = emptySet()
        )
        assertEquals(setOf("gone"), result)
    }

    @Test
    fun aSyncedLocalPhotoStillPresentRemotelyIsKept() {
        val result = photosToRemoveLocally(
            localPhotos = listOf(photo(id = "kept")),
            remoteClientIds = setOf("kept")
        )
        assertEquals(emptySet<String>(), result)
    }

    @Test
    fun aLegacyIdPhotoIsMatchedByItsCloudClientIdNotItsLocalId() {
        // Mirrors selectPullMatch's own legacy-id reasoning: the local id
        // ("shop-fan-photo-1") is never what got pushed as client_id for a
        // bundled/legacy product's photo -- only cloudClientId is.
        val local = photo(id = "shop-fan-photo-1", cloudClientId = "19ad4342-cloud-id")
        val stillThere = photosToRemoveLocally(listOf(local), remoteClientIds = setOf("19ad4342-cloud-id"))
        assertEquals(emptySet<String>(), stillThere)

        val nowGone = photosToRemoveLocally(listOf(local), remoteClientIds = emptySet())
        assertEquals(setOf("shop-fan-photo-1"), nowGone)
    }

    @Test
    fun aNeverSyncedLocalPhotoIsNeverRemovedEvenIfAbsentRemotely() {
        // The race this guard exists for: a photo just added on this device,
        // whose own push hasn't run (or hasn't reached the cloud) yet, must
        // never be read as "removed elsewhere" by a pull that happens to
        // land first. syncedAt == null is exactly what "not yet pushed"
        // means elsewhere in this same file (pushProduct's own upload gate).
        val justAdded = photo(id = "brand-new", syncedAt = null)
        val result = photosToRemoveLocally(listOf(justAdded), remoteClientIds = emptySet())
        assertEquals(emptySet<String>(), result)
    }

    @Test
    fun aMixOfPhotosOnlyRemovesTheSyncedOnesMissingRemotely() {
        val stillRemote = photo(id = "a")
        val removedElsewhere = photo(id = "b")
        val notYetPushed = photo(id = "c", syncedAt = null)
        val result = photosToRemoveLocally(
            localPhotos = listOf(stillRemote, removedElsewhere, notYetPushed),
            remoteClientIds = setOf("a")
        )
        assertEquals(setOf("b"), result)
    }

    @Test
    fun noLocalPhotosRemovesNothing() {
        val result = photosToRemoveLocally(localPhotos = emptyList(), remoteClientIds = setOf("a", "b"))
        assertEquals(emptySet<String>(), result)
    }
}
