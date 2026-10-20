package com.ballastlane.pokemon.service

import com.ballastlane.pokemon.client.PokeApiClient
import com.ballastlane.pokemon.config.CacheConfig
import com.ballastlane.pokemon.mapper.PokemonMapper
import com.ballastlane.pokemon.web.dto.PokemonSummary
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Component

/**
 * Loads single catalogue entries, cached by PokeAPI id.
 *
 * Kept as a separate bean from [PokemonCatalogService] so calls go through the caching proxy; a self-invocation
 * would silently bypass the cache.
 */
@Component
class PokemonSummaryLoader(
    private val pokeApiClient: PokeApiClient,
    private val pokemonMapper: PokemonMapper,
) {

    @Cacheable(cacheNames = [CacheConfig.POKEMON_SUMMARY_CACHE], key = "#id", sync = true)
    fun load(id: Int): PokemonSummary = pokemonMapper.toSummary(pokeApiClient.fetchPokemon(id.toString()))
}
