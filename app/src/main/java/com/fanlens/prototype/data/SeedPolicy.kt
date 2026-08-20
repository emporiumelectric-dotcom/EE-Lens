package com.fanlens.prototype.data

/**
 * Decides what the bundled catalogue contributes on launch.
 *
 * Two rules matter and both are easy to get wrong:
 *  - an app upgrade must not duplicate products that are already stored;
 *  - a product the owner deleted must stay deleted, even though it is still
 *    bundled inside the APK.
 */
object SeedPolicy {

    /** Raise this when a release adds new bundled products. */
    const val SEED_VERSION = 1

    fun shouldRun(recordedSeedVersion: Int): Boolean = recordedSeedVersion < SEED_VERSION

    /**
     * @param bundledIds every product id shipped in the APK
     * @param knownIds every id already in the database, including soft-deleted rows
     */
    fun idsToSeed(bundledIds: List<String>, knownIds: Set<String>): List<String> =
        bundledIds.filterNot(knownIds::contains)
}
