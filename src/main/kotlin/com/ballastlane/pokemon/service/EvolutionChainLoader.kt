package com.ballastlane.pokemon.service

import com.ballastlane.pokemon.client.PokeApiClient
import com.ballastlane.pokemon.config.CacheConfig
import com.ballastlane.pokemon.mapper.PokemonDetailsMapper
import com.ballastlane.pokemon.web.dto.EvolutionStage
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Component

/**
 * Loads evolution chains, cached by chain id so every member of a family shares one cached tree.
 *
 * The cached tree has no stage marked as current; that depends on the requested pokemon and is applied by
 * [PokemonDetailsService]. Kept as a separate bean so calls go through the caching proxy.
 */
@Component
class EvolutionChainLoader(
    private val pokeApiClient: PokeApiClient,
    private val detailsMapper: PokemonDetailsMapper,
) {

    @Cacheable(cacheNames = [CacheConfig.EVOLUTION_CHAIN_CACHE], key = "#chainId", sync = true)
    fun load(chainId: Int): EvolutionStage = detailsMapper.toEvolutionStage(pokeApiClient.fetchEvolutionChain(chainId))
}
