package com.ballastlane.pokemon.exception

/**
 * The pokemon is not in the local database, so its local data can't be updated (it may still exist in PokeAPI).
 */
class PokemonNotStoredLocallyException(nameOrId: String) :
    RuntimeException("Pokemon '$nameOrId' is not stored locally; look it up or synchronize it first")
