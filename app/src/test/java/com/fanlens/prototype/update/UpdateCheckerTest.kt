package com.fanlens.prototype.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * isNewer is the whole "Check for update" feature: it decides whether a
 * release tag counts as newer than the app's own BuildConfig.VERSION_NAME.
 *
 * The bug this guards against: the automated release workflow used to tag
 * every build "v<the hand-set versionName>" unchanged, so every release
 * compared equal to the one before it and an update could never be found.
 * The fix appends the GitHub Actions run number as a further dot-separated
 * *numeric* segment (e.g. "0.10.1" -> "0.10.1.157") -- these tests cover
 * exactly that shape, plus the reason a hyphenated build suffix was rejected
 * instead: isNewer silently drops any "." segment that isn't a plain
 * integer, so a non-numeric suffix doesn't just fail to help, it can make a
 * genuinely newer build compare as *not* newer.
 */
class UpdateCheckerTest {

    @Test
    fun anAutomatedReleaseIsNewerThanTheStillUnversionedInstallItReplaces() {
        // The exact real-world case: every install before this fix reports
        // plain "0.10.1"; the first fixed release tags "0.10.1.<run>".
        assertTrue(UpdateChecker.isNewer("0.10.1.157", "0.10.1"))
    }

    @Test
    fun eachSuccessiveAutomatedReleaseIsNewerThanTheLastOne() {
        assertTrue(UpdateChecker.isNewer("0.10.1.158", "0.10.1.157"))
        assertFalse(UpdateChecker.isNewer("0.10.1.157", "0.10.1.158"))
    }

    @Test
    fun anOldPlainTagIsNeverNewerThanAnAlreadyAutomatedInstall() {
        assertFalse(UpdateChecker.isNewer("0.10.1", "0.10.1.157"))
    }

    @Test
    fun aHandBumpedBaseVersionStillWinsOverAnyOldRunNumber() {
        // The developer bumping the checked-in base for a real milestone
        // must still be detected as newer than any earlier automated build,
        // whatever run number that earlier build happened to carry.
        assertTrue(UpdateChecker.isNewer("0.11.0.3", "0.10.1.999"))
    }

    @Test
    fun identicalVersionsAreNotNewer() {
        assertFalse(UpdateChecker.isNewer("0.10.1.157", "0.10.1.157"))
    }

    @Test
    fun aHyphenatedBuildSuffixIsExactlyWhyItWasNotUsedInstead() {
        // Documents the fragility that ruled out "v0.10.1-157": the "1-157"
        // segment isn't a plain integer, so it is silently dropped rather
        // than compared -- a genuinely newer build can come out looking
        // *not* newer. The dot-numeric segment this fix actually uses does
        // not have this problem (see the tests above).
        assertFalse(UpdateChecker.isNewer("0.10.1-157", "0.10.1"))
    }
}
