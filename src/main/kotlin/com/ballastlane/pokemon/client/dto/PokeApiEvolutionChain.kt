package com.ballastlane.pokemon.client.dto

import com.ballastlane.pokemon.client.dto.PokeApiPokemon.NamedResource
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Subset of the PokeAPI `/evolution-chain/{id}` payload that this service uses.
 */
@JvmRecord
@JsonIgnoreProperties(ignoreUnknown = true)
data class PokeApiEvolutionChain(val id: Int = 0, val chain: ChainLink? = null) {

    /**
     * One species in the chain, how it evolves from its parent, and what it evolves into.
     */
    @JvmRecord
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ChainLink(
        val species: NamedResource? = null,
        @JsonProperty("evolution_details") val evolutionDetails: List<EvolutionDetail>? = null,
        @JsonProperty("evolves_to") val evolvesTo: List<ChainLink>? = null,
    )

    @JvmRecord
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EvolutionDetail(
        val trigger: NamedResource? = null,
        @JsonProperty("min_level") val minLevel: Int? = null,
        val item: NamedResource? = null,
        @JsonProperty("held_item") val heldItem: NamedResource? = null,
        @JsonProperty("min_happiness") val minHappiness: Int? = null,
        @JsonProperty("time_of_day") val timeOfDay: String? = null,
    )
}
