package com.ballastlane.pokemon.sync

/**
 * Stops a full synchronization run early; the run is recorded as failed.
 */
internal class SyncAbortedException(message: String, cause: Throwable) : RuntimeException(message, cause)
