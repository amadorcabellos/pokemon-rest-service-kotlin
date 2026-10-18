package com.ballastlane.pokemon.service;

import com.ballastlane.pokemon.client.PokeApiClient;
import com.ballastlane.pokemon.config.CacheConfig;
import com.ballastlane.pokemon.mapper.PokemonDetailsMapper;
import com.ballastlane.pokemon.web.dto.EvolutionStage;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

/**
 * Loads evolution chains, cached by chain id so every member of a family shares one cached tree.
 * <p>
 * The cached tree has no stage marked as current; that depends on the requested pokemon and is applied by
 * {@link PokemonDetailsService}. Kept as a separate bean so calls go through the caching proxy.
 */
@Component
public class EvolutionChainLoader {

	private final PokeApiClient pokeApiClient;

	private final PokemonDetailsMapper detailsMapper;

	public EvolutionChainLoader(PokeApiClient pokeApiClient, PokemonDetailsMapper detailsMapper) {
		this.pokeApiClient = pokeApiClient;
		this.detailsMapper = detailsMapper;
	}

	@Cacheable(cacheNames = CacheConfig.EVOLUTION_CHAIN_CACHE, key = "#chainId", sync = true)
	public EvolutionStage load(int chainId) {
		return detailsMapper.toEvolutionStage(pokeApiClient.fetchEvolutionChain(chainId));
	}

}
