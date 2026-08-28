package com.fanlens.prototype.supabase

import com.fanlens.prototype.data.db.entity.ProductEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Covers selectPullMatch -- the decision a cloud pull uses to tell "this is
 * a product I already have" from "this is new". Getting it wrong either way
 * is a real bug: too loose silently merges two different products, too
 * strict duplicates one every single pull.
 *
 * The bug this guards against: the bundled demo catalogue seeds every
 * device with the same fixed slug ids (e.g. "havells-enticer-vineer",
 * never a UUID). When the PC pushes one, it invents its own random
 * client_id for it (see CloudSyncManager's class doc) -- a UUID the phone
 * has never seen and has no id-based way to recognise. Matching by id or
 * cloudClientId alone (what this used to do) misses every time, and the
 * phone inserts a second local copy on every pull.
 */
class CloudSyncManagerTest {

    private fun product(
        id: String,
        brand: String = "Havells",
        name: String = "Enticer Vineer",
        model: String = "1200 mm · Vineer",
        cloudClientId: String? = null,
        deletedAt: Long? = null,
        updatedAt: Long = 0L
    ) = ProductEntity(
        id = id,
        slug = id,
        brand = brand,
        name = name,
        model = model,
        category = null,
        colour = null,
        sizeSweepMm = null,
        priceMinor = null,
        mrpMinor = null,
        currency = "INR",
        description = "",
        specsJson = "{}",
        coverPhotoId = null,
        source = "user",
        createdAt = 0L,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
        cloudClientId = cloudClientId
    )

    @Test
    fun matchesByLocalIdWhenTheCloudRowIsThisDeviceSOwnEarlierPush() {
        val ownUuid = "38754c69-5055-4009-a2d9-180f756b744b"
        val mine = product(id = ownUuid)
        val someoneElses = product(id = "a166b45f-2fc2-4c92-8a3f-1adab7192262", name = "A different product")

        val match = selectPullMatch(ownUuid, mine.brand, mine.name, mine.model, listOf(someoneElses, mine))

        assertSame(mine, match)
    }

    @Test
    fun matchesByRecordedCloudClientIdForALegacyIdProductAlreadyReconciledOnce() {
        val remoteUuid = "19ad4342-39f2-4cff-a5c1-4408fc0707dc"
        val bundled = product(id = "havells-enticer-vineer", cloudClientId = remoteUuid)

        val match = selectPullMatch(remoteUuid, bundled.brand, bundled.name, bundled.model, listOf(bundled))

        assertSame(bundled, match)
    }

    @Test
    fun theBug_idOnlyMatchingMissesABundledProductPushedByAnotherDevice() {
        // This is the exact scenario from the field: the bundled catalogue,
        // seeded locally under its fixed slug, never pushed from this
        // device -- so it has no cloudClientId yet -- but already sitting
        // in the cloud under a UUID a *different* device generated for it.
        val remoteUuid = "19ad4342-39f2-4cff-a5c1-4408fc0707dc"
        val bundled = product(id = "havells-enticer-vineer", cloudClientId = null)

        // Neither id-based check can find it -- confirming this is genuinely
        // the scenario the fallback exists for, not a mistake in the fixture.
        assertNotEquals(remoteUuid, bundled.id)
        assertNotEquals(remoteUuid, bundled.cloudClientId)

        val match = selectPullMatch(remoteUuid, bundled.brand, bundled.name, bundled.model, listOf(bundled))

        // The content fallback recognises it anyway -- CloudSyncManager
        // updates this same row instead of inserting a duplicate.
        assertSame(bundled, match)
    }

    @Test
    fun pullingTheSameRowTwiceDoesNotDuplicateOnceTheMappingIsRecorded() {
        val remoteUuid = "19ad4342-39f2-4cff-a5c1-4408fc0707dc"
        var bundled = product(id = "havells-enticer-vineer", cloudClientId = null)
        val locals = mutableListOf(bundled)

        // First pull of this row: found only by content, exactly as above.
        val firstMatch = selectPullMatch(remoteUuid, bundled.brand, bundled.name, bundled.model, locals)
        assertSame(bundled, firstMatch)

        // What CloudSyncManager.pullProduct does next: persist the now-known
        // mapping onto that same local row (see its cloudClientId comment).
        bundled = bundled.copy(cloudClientId = remoteUuid)
        locals[0] = bundled

        // Second pull of the *same* remote row: must still resolve to the
        // one local row -- not null, which would mean a second row gets
        // inserted -- and now via the cheap cloudClientId check, no longer
        // needing the content fallback at all.
        val secondMatch = selectPullMatch(remoteUuid, bundled.brand, bundled.name, bundled.model, locals)
        assertSame(bundled, secondMatch)
        assertEquals(1, locals.size)
    }

    @Test
    fun aGenuinelyNewProductMatchesNothingSoItIsInsertedNotMerged() {
        val existing = product(id = "havells-enticer-vineer")
        val match = selectPullMatch(
            clientId = "c03fd128-cf47-446e-bc00-c92b3740f3ad",
            brand = "Atomberg",
            name = "Renesa Prime Remote Ceiling Fan",
            model = "",
            locals = listOf(existing)
        )
        assertNull(match)
    }

    @Test
    fun contentFallbackNeverRevivesAProductTheOwnerDeletedHere() {
        val remoteUuid = "19ad4342-39f2-4cff-a5c1-4408fc0707dc"
        val deleted = product(id = "havells-enticer-vineer", cloudClientId = null, deletedAt = 12345L)

        val match = selectPullMatch(remoteUuid, deleted.brand, deleted.name, deleted.model, listOf(deleted))

        assertNull(match)
    }

    @Test
    fun contentFallbackNeverRunsWithoutABrandAndName() {
        val remoteUuid = "19ad4342-39f2-4cff-a5c1-4408fc0707dc"
        val local = product(id = "havells-enticer-vineer", brand = "", name = "")

        assertNull(selectPullMatch(remoteUuid, "", "", "", listOf(local)))
    }
}
