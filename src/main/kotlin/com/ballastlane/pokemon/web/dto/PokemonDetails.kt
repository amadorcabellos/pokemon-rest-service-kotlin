package com.ballastlane.pokemon.web.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * Detailed view of a pokemon: image, base stats, Pokedex description and evolution chain, plus its local
 * (proprietary) data.
 *
 * @property localData always read fresh from PostgreSQL; `null` in the cached value, which only holds the PokeAPI
 * part
 */
data class PokemonDetails(
    val id: Int,
    val name: String?,
    val imageUrl: String?,
    val stats: List<Stat>,
    val totalStats: Int,
    val description: String?,
    val evolution: EvolutionStage,
    @Schema(description = "Proprietary data owned by this service; changed with PUT or PATCH on this resource")
    val localData: LocalData? = null,
) {

    fun withLocalData(localData: LocalData): PokemonDetails = copy(localData = localData)
}
