package com.fanlens.prototype.supabase

import com.fanlens.prototype.data.db.entity.ProductEntity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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
        colour: String? = null,
        sizeSweepMm: Int? = null,
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
        colour = colour,
        sizeSweepMm = sizeSweepMm,
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

        val match = selectPullMatch(
            ownUuid, mine.brand, mine.name, mine.model, mine.colour, mine.sizeSweepMm, listOf(someoneElses, mine)
        )

        assertSame(mine, match)
    }

    @Test
    fun matchesByRecordedCloudClientIdForALegacyIdProductAlreadyReconciledOnce() {
        val remoteUuid = "19ad4342-39f2-4cff-a5c1-4408fc0707dc"
        val bundled = product(id = "havells-enticer-vineer", cloudClientId = remoteUuid)

        val match = selectPullMatch(
            remoteUuid, bundled.brand, bundled.name, bundled.model, bundled.colour, bundled.sizeSweepMm, listOf(bundled)
        )

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

        val match = selectPullMatch(
            remoteUuid, bundled.brand, bundled.name, bundled.model, bundled.colour, bundled.sizeSweepMm, listOf(bundled)
        )

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
        val firstMatch = selectPullMatch(
            remoteUuid, bundled.brand, bundled.name, bundled.model, bundled.colour, bundled.sizeSweepMm, locals
        )
        assertSame(bundled, firstMatch)

        // What CloudSyncManager.pullProduct does next: persist the now-known
        // mapping onto that same local row (see its cloudClientId comment).
        bundled = bundled.copy(cloudClientId = remoteUuid)
        locals[0] = bundled

        // Second pull of the *same* remote row: must still resolve to the
        // one local row -- not null, which would mean a second row gets
        // inserted -- and now via the cheap cloudClientId check, no longer
        // needing the content fallback at all.
        val secondMatch = selectPullMatch(
            remoteUuid, bundled.brand, bundled.name, bundled.model, bundled.colour, bundled.sizeSweepMm, locals
        )
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
            colour = null,
            sizeSweepMm = null,
            locals = listOf(existing)
        )
        assertNull(match)
    }

    @Test
    fun contentFallbackNeverRevivesAProductTheOwnerDeletedHere() {
        val remoteUuid = "19ad4342-39f2-4cff-a5c1-4408fc0707dc"
        val deleted = product(id = "havells-enticer-vineer", cloudClientId = null, deletedAt = 12345L)

        val match = selectPullMatch(
            remoteUuid, deleted.brand, deleted.name, deleted.model, deleted.colour, deleted.sizeSweepMm, listOf(deleted)
        )

        assertNull(match)
    }

    @Test
    fun contentFallbackNeverRunsWithoutABrandAndName() {
        val remoteUuid = "19ad4342-39f2-4cff-a5c1-4408fc0707dc"
        val local = product(id = "havells-enticer-vineer", brand = "", name = "")

        assertNull(selectPullMatch(remoteUuid, "", "", "", null, null, listOf(local)))
    }

    // ---------------- colour/size must agree too, not just brand/name/model ----------------

    @Test
    fun theBug_twoDifferentColourVariantsSharingBrandNameModelMustNotMerge() {
        // The exact real incident: "Havells Cera Underlight BLDC Ceiling
        // Fan" / "FHCCTE5CPG48-c" pushed from the PC as two genuinely
        // different colour variants (champagne and MIST), sharing an
        // identical brand/name/model. Before this fix, selectPullMatch's
        // content fallback folded the second pull onto the first local row
        // regardless of colour -- silently merging two different products,
        // and (see CloudSyncManager's withTransaction comment) crashing the
        // pull outright whenever a photo happened to collide too.
        val champagne = product(
            id = "havells-cera-underlight-champagne",
            name = "Cera Underlight BLDC Ceiling Fan",
            model = "FHCCTE5CPG48-c",
            colour = "champagne",
            sizeSweepMm = 1200
        )

        val match = selectPullMatch(
            clientId = "50f32d39-dd68-4c3d-8164-837b47201113", // the MIST row's own client_id
            brand = champagne.brand,
            name = champagne.name,
            model = champagne.model,
            colour = "MIST",
            sizeSweepMm = 1200,
            locals = listOf(champagne)
        )

        assertNull("a different colour must never match, even with identical brand/name/model", match)
    }

    @Test
    fun theBug_twoDifferentSizesOfTheSameModelMustNotMerge() {
        // The same hazard the user flagged for size: the same fan model is
        // commonly sold in more than one sweep size (e.g. 1200mm/1400mm),
        // sharing brand/name/model just like a colour variant does.
        val size1200 = product(
            id = "havells-enticer-1200",
            name = "Enticer",
            model = "GHFENTBLK1200",
            colour = "Pearl White",
            sizeSweepMm = 1200
        )

        val match = selectPullMatch(
            clientId = "a1a1a1a1-1111-1111-1111-111111111111",
            brand = size1200.brand,
            name = size1200.name,
            model = size1200.model,
            colour = size1200.colour,
            sizeSweepMm = 1400,
            locals = listOf(size1200)
        )

        assertNull("a different size must never match, even with identical brand/name/model/colour", match)
    }

    @Test
    fun theFix_theSameColourAndSizeStillMatchesAsBefore() {
        // Confirms the fix is additive, not a regression: the ordinary case
        // (a real re-pull of the exact same variant) must still match.
        val bundled = product(
            id = "havells-enticer-vineer",
            colour = "Vineer",
            sizeSweepMm = 1200
        )

        val match = selectPullMatch(
            clientId = "19ad4342-39f2-4cff-a5c1-4408fc0707dc",
            brand = bundled.brand,
            name = bundled.name,
            model = bundled.model,
            colour = "vineer", // different case -- must still match, like brand/name/model
            sizeSweepMm = 1200,
            locals = listOf(bundled)
        )

        assertSame(bundled, match)
    }

    @Test
    fun bothSidesHavingNoColourStillMatches() {
        // Neither a genuinely colourless product (some categories have none)
        // nor a size-less one should be permanently unmatchable.
        val local = product(id = "havells-power-hunk-mixer", name = "Power Hunk Mixer Grinder", model = "GHFMGDPK080-c")

        val match = selectPullMatch(
            clientId = "c4c4c4c4-4444-4444-4444-444444444444",
            brand = local.brand,
            name = local.name,
            model = local.model,
            colour = null,
            sizeSweepMm = null,
            locals = listOf(local)
        )

        assertSame(local, match)
    }

    // ---------------- photoUpsertBody ----------------

    @Test
    fun photoUpsertBodyAlwaysIncludesStoragePathEvenForAnAlreadySyncedPhoto() {
        // The real bug this guards against: storage_path used to be left
        // OUT of this body entirely whenever the photo was already synced
        // (syncedAt != null), on the theory that omitting a JSON key on a
        // repeat push meant "leave whatever the column already holds
        // alone". PostgREST's resolution=merge-duplicates upsert does not
        // honour that: it still builds a full row for its ON CONFLICT DO
        // UPDATE, and an omitted column with no default -- storage_path has
        // none -- is written as NULL. storage_path is NOT NULL, so every
        // second-or-later push of any product with at least one already-
        // synced photo 400'd on that constraint, silently: the exception
        // propagated out of pushProduct, was swallowed by
        // cloudBackgroundPush's catch block, and "last pushed" simply never
        // advanced -- exactly the field symptom this test exists to catch
        // (a stale "Last pushed" timestamp on a plain repeat push, not just
        // a photo-removal edit). storagePath is a pure function of
        // clientId/photoClientId (see SupabaseSyncClient.photoStoragePath),
        // so there is no reason it should ever depend on whether this is
        // the photo's first push or its fifth.
        val body = photoUpsertBody(
            photoClientId = "8f14e45f-ceea-467e-9e0f-936c5e6b4e5c",
            remoteProductId = 42L,
            cloudRole = "catalogue",
            sortOrder = 0,
            checksum = "deadbeef",
            storagePath = "11111111-1111-1111-1111-111111111111/8f14e45f-ceea-467e-9e0f-936c5e6b4e5c.jpg"
        )

        assertTrue("storage_path must always be present, never omitted", body.has("storage_path"))
        assertEquals(
            "11111111-1111-1111-1111-111111111111/8f14e45f-ceea-467e-9e0f-936c5e6b4e5c.jpg",
            body.getString("storage_path")
        )
        // Every other field this body carries, unaffected by this fix.
        assertEquals("8f14e45f-ceea-467e-9e0f-936c5e6b4e5c", body.getString("client_id"))
        assertEquals(42L, body.getLong("product_id"))
        assertEquals("catalogue", body.getString("role"))
        assertEquals(0, body.getInt("sort_order"))
        assertEquals("deadbeef", body.getString("checksum"))
    }

    // ---------------- isDeletedRow / pullAll's two-pass ordering ----------------

    private fun remoteRow(deletedAt: String? = null) = JSONObject().apply {
        put("deleted_at", deletedAt ?: JSONObject.NULL)
    }

    @Test
    fun isDeletedRowIsTrueOnlyWhenDeletedAtIsAPresentNonBlankValue() {
        assertTrue(isDeletedRow(remoteRow(deletedAt = "2026-08-28T17:57:54.571445+00:00")))
        assertFalse(isDeletedRow(remoteRow(deletedAt = null)))
        assertFalse(isDeletedRow(remoteRow(deletedAt = "")))
    }

    @Test
    fun twoPassOrderingPutsEveryActiveRowBeforeEveryDeletedRowRegardlessOfInputOrder() {
        // Mirrors the exact bug: a duplicate row (deleted) can have an OLDER
        // updated_at than the still-active row for the same real product, so
        // fetching with order=updated_at.asc alone puts the deleted row
        // first -- see pullAll's own comment. This is the same expression
        // pullAll uses, isolated so the ordering guarantee itself is
        // regression-tested without needing Room or a network.
        val rows = listOf(
            remoteRow(deletedAt = "2026-08-28T17:01:09.935+00:00"), // the duplicate, deleted, but OLDER
            remoteRow(deletedAt = null),                             // the real, active row -- fetched second
            remoteRow(deletedAt = null)                              // another unrelated active row
        )

        val order = rows.indices.sortedBy { isDeletedRow(rows[it]) }

        // Both active rows (indices 1 and 2) come before the deleted one
        // (index 0), and their own relative order is preserved (stable sort).
        assertEquals(listOf(1, 2, 0), order)
    }

    @Test
    fun aDeviceAlreadySoftDeletedByThePreFixOrderingBugSelfHealsOnTheNextPull() {
        // Follow-up investigation, after the two-pass fix (PR #13) shipped
        // and the reported symptom persisted on the one device that had
        // already been hit by the pre-fix bug. This proves what the code
        // actually does to that device's already-corrupted local row on its
        // next pull -- not what it does to a clean device (already covered
        // by the scenarios above).
        //
        // That device's real state, reconstructed from the live incident:
        // its local product still sits under the original slug id, soft-
        // deleted (by the pre-fix pull processing the older, deleted
        // duplicate row before the newer, active one), with cloudClientId
        // still pointing at that now-deleted duplicate's client_id -- never
        // corrected, because nothing has matched this row since.
        val duplicateClientId = "50c58588-ee35-4a32-bd33-fd017853b092"
        val authoritativeClientId = "63ee3258-a788-483a-b28c-764ebd9e612a"
        val corrupted = product(
            id = "havells-stealth-air-pearl-white",
            brand = "Havells",
            name = "Havells Stealth Air",
            model = "1200 mm · Pearl White",
            cloudClientId = duplicateClientId,
            deletedAt = 1787978274571L // already soft-deleted by the earlier bad pull
        )

        // On the next pull, the active (authoritative) row is now processed
        // first, exactly as intended -- but selectPullMatch still can't find
        // this local row for it: id and cloudClientId both miss (neither
        // equals the authoritative row's client_id), and the content
        // fallback explicitly excludes already-deleted local rows.
        val match = selectPullMatch(
            authoritativeClientId, corrupted.brand, corrupted.name, corrupted.model,
            corrupted.colour, corrupted.sizeSweepMm, listOf(corrupted)
        )

        // So pullProduct treats the authoritative row as a genuinely new
        // product and inserts it fresh under its own client_id as the local
        // id -- the corrupted row is simply left behind, orphaned but
        // harmless (soft-deleted, never revisited again). The device ends
        // up with the product back, correctly, just under a new local id --
        // this is the fix actually self-healing an already-corrupted
        // device, not a case that stays broken.
        assertNull(match)
    }
}
