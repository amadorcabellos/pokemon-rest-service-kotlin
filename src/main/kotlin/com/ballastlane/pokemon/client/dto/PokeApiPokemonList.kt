package com.ballastlane.pokemon.client.dto

import com.ballastlane.pokemon.client.PokeApiUrls
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls

/**
 * Subset of the PokeAPI `/pokemon?offset&limit` payload: the catalogue size and one page of entries.
 */
@JvmRecord
@JsonIgnoreProperties(ignoreUnknown = true)
data class PokeApiPokemonList(
    val count: Int = 0,
    @JsonSetter(nulls = Nulls.AS_EMPTY) val results: List<Entry> = emptyList(),
) {

    @JvmRecord
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Entry(val name: String, val url: String) {

        /**
         * PokeAPI id, taken from the last path segment of the detail URL (e.g. `.../pokemon/25/`).
         */
        val id: Int
            get() = PokeApiUrls.idFrom(url)
    }
}
