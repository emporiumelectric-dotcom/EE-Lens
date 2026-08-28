package com.fanlens.prototype.supabase

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers pushWouldLoseToRemote -- the last-write-wins rule a push checks
 * before it upserts. Without it, a device pushing a stale local edit after
 * another device already pushed a newer one would silently clobber the
 * newer edit; pullAll already refuses the same thing in the other
 * direction (see CloudSyncManagerTest), but push had no such guard.
 */
class PushConflictPolicyTest {

    @Test
    fun aProductNeverPushedBeforeAlwaysWins() {
        // No remote row yet -- nothing to lose a race against.
        assertFalse(pushWouldLoseToRemote(remoteUpdatedAt = null, localUpdatedAt = 1_000L))
    }

    @Test
    fun aLocalEditNewerThanTheRemoteRowWins() {
        assertFalse(pushWouldLoseToRemote(remoteUpdatedAt = 1_000L, localUpdatedAt = 2_000L))
    }

    @Test
    fun aLocalEditOlderThanTheRemoteRowLoses() {
        // The exact scenario from the field: device A edited a product, then
        // device B edited the same product later and pushed first. Device A's
        // stale push must not overwrite device B's newer one.
        assertTrue(pushWouldLoseToRemote(remoteUpdatedAt = 2_000L, localUpdatedAt = 1_000L))
    }

    @Test
    fun anExactTieGoesToTheRemoteSideSoAPushIsNeverAnNoOpRace() {
        // Ties are rare (millisecond timestamps) but must resolve one way,
        // consistently -- and consistently with pullAll's own ">=" check.
        assertTrue(pushWouldLoseToRemote(remoteUpdatedAt = 1_000L, localUpdatedAt = 1_000L))
    }
}
