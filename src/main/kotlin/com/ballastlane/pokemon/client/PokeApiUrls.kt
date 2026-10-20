package com.ballastlane.pokemon.client

/**
 * Helpers for PokeAPI resource URLs.
 */
object PokeApiUrls {

    /**
     * Resource id taken from the last path segment of a PokeAPI URL (e.g. `.../pokemon-species/25/` → 25).
     */
    fun idFrom(url: String): Int = url.removeSuffix("/").substringAfterLast('/').toInt()
}
