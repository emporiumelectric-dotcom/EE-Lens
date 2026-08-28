package com.fanlens.prototype.supabase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * cloudRefreshStatusMessage is what stands between a pull-to-refresh failure
 * and it being completely invisible -- the actual bug report this fixes: on
 * a real device, swiping down showed no change and "Last pulled" never
 * updated, with nothing anywhere (not even Logcat) saying why. The call site
 * (ElectricEmporiumScreen's onRefresh) now always logs a failed pull and
 * shows whatever this function decides is worth telling the owner.
 *
 * Can't cover here, and still needs a real device: whether the swipe gesture
 * itself reaches onRefresh at all, and what the real exception turns out to
 * be against the actual Supabase backend -- this only covers the decision of
 * what to say once a Result is already in hand.
 */
class CloudRefreshStatusTest {

    private fun summary(processed: Int, failed: Int, total: Int) =
        CloudSyncManager.SyncSummary(processed = processed, failed = failed, total = total)

    @Test
    fun aCleanPullHasNothingToSay() {
        val result = Result.success(summary(processed = 5, failed = 0, total = 5))
        assertNull(cloudRefreshStatusMessage(result))
    }

    @Test
    fun anEmptyCatalogueIsAlsoACleanPull() {
        // Nothing in the cloud yet is not a failure.
        val result = Result.success(summary(processed = 0, failed = 0, total = 0))
        assertNull(cloudRefreshStatusMessage(result))
    }

    @Test
    fun anExceptionMeansTheCloudWasNeverReached() {
        val result = Result.failure<CloudSyncManager.SyncSummary>(SupabaseSyncException("boom"))
        val message = cloudRefreshStatusMessage(result)
        assertTrue(message != null && message.contains("Couldn't reach the cloud"))
    }

    @Test
    fun anyFailureGetsTheSameFriendlyMessageRegardlessOfWhatThrew() {
        // Deliberately ignores the exception's own message (which can be null,
        // e.g. a real NetworkOnMainThreadException, or an ugly raw string not
        // fit for the screen) -- the raw error still reaches Logcat at the call
        // site, this only has to never be blank on screen.
        val result = Result.failure<CloudSyncManager.SyncSummary>(RuntimeException())
        val message = cloudRefreshStatusMessage(result)
        assertTrue(message != null && message.isNotBlank())
    }

    @Test
    fun someRowsFailingIsReportedWithCounts() {
        val result = Result.success(summary(processed = 3, failed = 2, total = 5))
        assertEquals("Cloud pull failed for 2 of 5 products", cloudRefreshStatusMessage(result))
    }

    @Test
    fun everyRowFailingIsStillJustTheCountsNotAWorseMessage() {
        val result = Result.success(summary(processed = 0, failed = 5, total = 5))
        assertEquals("Cloud pull failed for 5 of 5 products", cloudRefreshStatusMessage(result))
    }

    @Test
    fun aSingleFailingRowUsesTheSingularNoun() {
        val result = Result.success(summary(processed = 0, failed = 1, total = 1))
        assertEquals("Cloud pull failed for 1 of 1 product", cloudRefreshStatusMessage(result))
    }
}
