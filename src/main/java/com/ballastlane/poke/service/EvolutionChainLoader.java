package com.ballastlane.poke.service;

import com.ballastlane.poke.client.PokeApiClient;
import com.ballastlane.poke.config.CacheConfig;
import com.ballastlane.poke.mapper.PokemonDetailsMapper;
import com.ballastlane.poke.web.dto.EvolutionStage;
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
