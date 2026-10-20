package com.ballastlane.pokemon.client.dto

import com.ballastlane.pokemon.client.dto.PokeApiPokemon.NamedResource
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Subset of the PokeAPI `/pokemon-species/{id}` payload that this service uses.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class PokeApiSpecies(
    val id: Int = 0,
    val name: String? = null,
    @JsonProperty("flavor_text_entries") val flavorTextEntries: List<FlavorText>? = null,
    @JsonProperty("evolution_chain") val evolutionChain: Resource? = null,
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class FlavorText(
        @JsonProperty("flavor_text") val flavorText: String? = null,
        val language: NamedResource? = null,
        val version: NamedResource? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Resource(val url: String? = null)
}
