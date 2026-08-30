package com.fanlens.prototype.supabase

import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Real bug, found investigating "Cloud pull failed for 1 of 19 products":
 * pullProduct's transaction (CloudSyncManager.kt) currently INSERTS a
 * product's new photos before it DELETES the stale ones being replaced.
 * PhotoEntity's own Index(["product_id", "sha256"], unique = true) is
 * enforced immediately per statement, not deferred to commit -- so
 * inserting a new photo whose bytes (sha256) exactly match a stale photo
 * still sitting there throws before the stale row is ever removed.
 *
 * This happens for real whenever selectPullMatch's content fallback (same
 * brand/name/model, different client_id -- see CloudSyncManagerTest) maps
 * two genuinely different remote products onto the same local row, and the
 * newer push happened to reuse one of the same image files as the older
 * one. Confirmed live: "Havells Cera Underlight BLDC Ceiling Fan" was
 * pushed from the PC twice (remote ids 98 and 101, different client_ids),
 * and one of 101's 9 photos shares the exact sha256 of one of 98's
 * already-local 3 -- exactly the fixture below. Because the whole
 * transaction rolls back on this error, the local row's updated_at never
 * advances, so every retry re-downloads all 9 photos over the network and
 * hits the exact same violation again -- forever, not a one-off flake.
 * That is what surfaced as "1 of 19" in cloudRefreshStatusMessage.
 *
 * Room compiles PhotoEntity's schema to plain SQLite on-device, so this
 * runs the same engine and the same constraint pullProduct actually hits
 * -- not a stand-in for it (see build.gradle.kts's org.xerial:sqlite-jdbc
 * comment, and testImplementation("org.json:json...")'s own precedent for
 * testing against the real thing rather than a stub).
 */
class PhotoPullOrderingTest {

    private lateinit var conn: Connection

    private val localProductId = "0b169f19-6cb2-41da-9cee-fb7e4c2ef53f" // local row, originally pulled from remote row 98

    // The 3 photos this local row already has, from product 98's original pull.
    private val existingPhotos = listOf(
        "6370682a-1011-4e38-a6f6-fa783d6eb279" to "6f59915ed72b36cc31449d978f7eb5d6716762d240ca34f342044eda28d1e75a",
        "45854b56-2b5d-4d02-ad78-033e7c5cc63b" to "02d23cd35dc85ca54ea9f935fefebad36137164383c25b392d117ba64226ec32", // colliding one
        "024b8bc3-b004-461e-b829-2a074e494932" to "03b177645540b675a57551d4e8db1a4662b25d466377f52128985ca04abc2498"
    )

    // Remote row 101's own 9 photos, mapped onto the SAME local row by the
    // content fallback. Real checksums: 9a43ad50... genuinely reuses
    // 02d23cd3...'s exact bytes.
    private val newPhotos = listOf(
        "75fe08c6-9274-41a4-a639-1cd2181c93db" to "395d8857fb944b881a61546dd62b579854799ee102d4845544ec9c0070941880",
        "9a43ad50-b43d-4298-9033-257c6929f343" to "02d23cd35dc85ca54ea9f935fefebad36137164383c25b392d117ba64226ec32", // collides!
        "746daf12-a1a3-414d-b620-16276ef101b3" to "627da0ddc239619284ae712bd9cf2444f2ac412668cf1f35cb935799a0595030",
        "893a77f4-15f3-4a2c-8dae-e740b6e7ca15" to "2000e605048aa942f81b2caff47b3b0ff64b32e669b9bddf8c58a0950c176bf3",
        "8351f7b9-4713-4ab7-a5b7-f2325dd2da75" to "d972692312998dfda4f39ea6b7931646029f25239c5e1ad8ffb650d164760674",
        "626de360-f3f6-49c7-b90d-be521e49bc37" to "160d9ee89157550974d2e00e5b07c635c79e379adb0d229731bc38b52d641e56",
        "6b0766bd-50b9-4047-b944-3ea4e2f2c683" to "6c3cfbd90727cf73fe86872eeb1850af7194cf255818d03ba23322d1e5bf9a58",
        "ea3b124c-d279-42f1-91d3-0f3cef28a4df" to "b3773e440457cb00c598b88a25b605611abbd226d938399b2c6fbe83d34537a6",
        "264e7663-fecb-42e9-b1b8-d0bcddccc50c" to "131a2b8ee9e55f0d311e63b7fde2fe3be09c4c5464d7acb69fa6a0f33b687678"
    )

    private val staleIds = existingPhotos.map { it.first }

    @Before
    fun setUp() {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        conn.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
        conn.createStatement().use {
            it.execute(
                """
                CREATE TABLE products (
                  id TEXT PRIMARY KEY,
                  updated_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
            it.execute(
                """
                CREATE TABLE photos (
                  id TEXT PRIMARY KEY,
                  product_id TEXT NOT NULL REFERENCES products(id) ON DELETE CASCADE,
                  sha256 TEXT NOT NULL
                )
                """.trimIndent()
            )
            // Mirrors PhotoEntity's real Index(["product_id", "sha256"], unique = true).
            it.execute("CREATE UNIQUE INDEX index_photos_product_id_sha256 ON photos(product_id, sha256)")
        }
        conn.prepareStatement("INSERT INTO products VALUES (?, 0)").use { stmt ->
            stmt.setString(1, localProductId)
            stmt.executeUpdate()
        }
        for ((id, sha) in existingPhotos) insertPhoto(id, sha)
    }

    @After
    fun tearDown() {
        conn.close()
    }

    private fun insertPhoto(id: String, sha256: String) {
        conn.prepareStatement("INSERT INTO photos VALUES (?, ?, ?)").use { stmt ->
            stmt.setString(1, id)
            stmt.setString(2, localProductId)
            stmt.setString(3, sha256)
            stmt.executeUpdate()
        }
    }

    private fun deletePhoto(id: String) {
        conn.prepareStatement("DELETE FROM photos WHERE id = ?").use { stmt ->
            stmt.setString(1, id)
            stmt.executeUpdate()
        }
    }

    private fun photoCount(): Int {
        conn.prepareStatement("SELECT COUNT(*) FROM photos WHERE product_id = ?").use { stmt ->
            stmt.setString(1, localProductId)
            stmt.executeQuery().use { rs ->
                rs.next()
                return rs.getInt(1)
            }
        }
    }

    @Test
    fun theBug_insertingNewPhotosBeforeDeletingStaleOnesThrowsOnACollidingChecksum() {
        // pullProduct's PRE-FIX order: newPhotos.forEach { insert }, then
        // staleLocalPhotoIds.forEach { delete }.
        try {
            for ((id, sha) in newPhotos) insertPhoto(id, sha)
            for (id in staleIds) deletePhoto(id)
            fail("expected a UNIQUE constraint violation on the colliding checksum, but the insert-then-delete order succeeded")
        } catch (e: SQLException) {
            assertTrue(
                "expected the real unique-index violation, got: ${e.message}",
                (e.message ?: "").contains("UNIQUE constraint failed", ignoreCase = true) ||
                    (e.message ?: "").contains("unique", ignoreCase = true)
            )
        }
    }

    @Test
    fun theFix_deletingStalePhotosBeforeInsertingNewOnesSucceeds() {
        // The fix: staleLocalPhotoIds.forEach { delete }, then newPhotos.forEach { insert }.
        for (id in staleIds) deletePhoto(id)
        for ((id, sha) in newPhotos) insertPhoto(id, sha)

        assertEquals(
            "all 9 of remote row 101's photos must end up stored locally, no constraint violation",
            newPhotos.size,
            photoCount()
        )
    }
}
