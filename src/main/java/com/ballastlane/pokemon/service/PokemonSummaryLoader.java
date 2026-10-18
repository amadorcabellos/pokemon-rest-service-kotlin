package com.ballastlane.pokemon.service;

import com.ballastlane.pokemon.client.PokeApiClient;
import com.ballastlane.pokemon.config.CacheConfig;
import com.ballastlane.pokemon.mapper.PokemonMapper;
import com.ballastlane.pokemon.web.dto.PokemonSummary;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

/**
 * Loads single catalogue entries, cached by PokeAPI id.
 * <p>
 * Kept as a separate bean from {@link PokemonCatalogService} so calls go through the caching proxy;
 * a self-invocation would silently bypass the cache.
 */
@Component
public class PokemonSummaryLoader {

	private final PokeApiClient pokeApiClient;

	private final PokemonMapper pokemonMapper;

	public PokemonSummaryLoader(PokeApiClient pokeApiClient, PokemonMapper pokemonMapper) {
		this.pokeApiClient = pokeApiClient;
		this.pokemonMapper = pokemonMapper;
	}

	@Cacheable(cacheNames = CacheConfig.POKEMON_SUMMARY_CACHE, key = "#id", sync = true)
	public PokemonSummary load(int id) {
		return pokemonMapper.toSummary(pokeApiClient.fetchPokemon(String.valueOf(id)));
	}

}
