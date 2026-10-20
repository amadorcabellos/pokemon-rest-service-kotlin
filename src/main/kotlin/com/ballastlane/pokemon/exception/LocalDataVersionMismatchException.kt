package com.ballastlane.pokemon.exception

/**
 * The `If-Match` of a local data update doesn't match the current version, so the update was not applied.
 */
class LocalDataVersionMismatchException(val currentVersion: Long) :
    RuntimeException("The local data has changed since it was read; it is now at version $currentVersion")
