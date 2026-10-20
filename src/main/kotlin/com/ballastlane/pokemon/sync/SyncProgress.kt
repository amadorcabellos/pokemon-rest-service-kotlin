package com.ballastlane.pokemon.sync

/**
 * Counts of a full synchronization run so far.
 */
data class SyncProgress(val catalogueCount: Int, val created: Int, val updated: Int, val failed: Int)
