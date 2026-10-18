package com.ballastlane.pokemon.sync;

/**
 * Counts of a full synchronization run so far.
 */
record SyncProgress(int catalogueCount, int created, int updated, int failed) {
}
