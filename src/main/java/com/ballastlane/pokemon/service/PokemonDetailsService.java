package com.ballastlane.pokemon.service;

import com.ballastlane.pokemon.client.PokeApiClient;
import com.ballastlane.pokemon.client.PokeApiUrls;
import com.ballastlane.pokemon.client.dto.PokeApiPokemon;
import com.ballastlane.pokemon.client.dto.PokeApiSpecies;
import com.ballastlane.pokemon.config.CacheConfig;
import com.ballastlane.pokemon.mapper.PokemonDetailsMapper;
import com.ballastlane.pokemon.mapper.PokemonMapper;
import com.ballastlane.pokemon.repository.PokemonRepository;
import com.ballastlane.pokemon.web.dto.EvolutionStage;
import com.ballastlane.pokemon.web.dto.PokemonDetails;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Builds the detailed view of a pokemon from PokeAPI (pokemon → species → evolution chain) and records the basic
 * pokemon in PostgreSQL the first time it is looked up.
 */
@Service
public class PokemonDetailsService {

	private static final Logger log = LoggerFactory.getLogger(PokemonDetailsService.class);

	private final PokeApiClient pokeApiClient;

	private final EvolutionChainLoader evolutionChainLoader;

	private final PokemonRepository pokemonRepository;

	private final PokemonMapper pokemonMapper;

	private final PokemonDetailsMapper detailsMapper;

	public PokemonDetailsService(PokeApiClient pokeApiClient, EvolutionChainLoader evolutionChainLoader,
			PokemonRepository pokemonRepository, PokemonMapper pokemonMapper, PokemonDetailsMapper detailsMapper) {
		this.pokeApiClient = pokeApiClient;
		this.evolutionChainLoader = evolutionChainLoader;
		this.pokemonRepository = pokemonRepository;
		this.pokemonMapper = pokemonMapper;
		this.detailsMapper = detailsMapper;
	}

	/**
	 * Not transactional on purpose: PokeAPI calls (including retries) must not hold a database connection.
	 * The save runs in its own short transaction. If any PokeAPI call fails, nothing is cached.
	 */
	@Cacheable(cacheNames = CacheConfig.POKEMON_DETAILS_CACHE, key = "#nameOrId.trim().toLowerCase()", sync = true)
	public PokemonDetails getPokemon(String nameOrId) {
		String key = nameOrId.trim().toLowerCase(Locale.ROOT);
		PokeApiPokemon pokemon = pokeApiClient.fetchPokemon(key);
		recordIfNew(pokemon);

		// The species (not the pokemon name) links description and evolution, so alternate forms resolve correctly.
		PokeApiSpecies species = pokeApiClient.fetchSpecies(PokeApiUrls.idFrom(pokemon.species().url()));
		EvolutionStage evolution = species.evolutionChain() != null
				? evolutionChainLoader.load(PokeApiUrls.idFrom(species.evolutionChain().url()))
				: detailsMapper.singleStage(species);

		return detailsMapper.toDetails(pokemon, species, evolution);
	}

	private void recordIfNew(PokeApiPokemon pokemon) {
		if (pokemonRepository.findByPokeApiId(pokemon.id()).isPresent()) {
			return;
		}
		try {
			pokemonRepository.save(pokemonMapper.toEntity(pokemon));
		}
		catch (DataIntegrityViolationException ex) {
			// A concurrent first lookup stored it already.
			log.debug("Pokemon {} was stored concurrently", pokemon.id());
		}
	}

}
