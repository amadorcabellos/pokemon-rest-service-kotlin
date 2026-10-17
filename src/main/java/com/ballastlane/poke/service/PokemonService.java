package com.ballastlane.poke.service;

import com.ballastlane.poke.client.PokeApiClient;
import com.ballastlane.poke.config.CacheConfig;
import com.ballastlane.poke.domain.Pokemon;
import com.ballastlane.poke.mapper.PokemonMapper;
import com.ballastlane.poke.repository.PokemonRepository;
import com.ballastlane.poke.web.dto.PokemonResponse;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves pokemon using a read-through strategy: Redis cache, then PostgreSQL, then PokeAPI.
 */
@Service
public class PokemonService {

	private static final Logger log = LoggerFactory.getLogger(PokemonService.class);

	private final PokeApiClient pokeApiClient;

	private final PokemonRepository pokemonRepository;

	private final PokemonMapper pokemonMapper;

	public PokemonService(PokeApiClient pokeApiClient, PokemonRepository pokemonRepository,
			PokemonMapper pokemonMapper) {
		this.pokeApiClient = pokeApiClient;
		this.pokemonRepository = pokemonRepository;
		this.pokemonMapper = pokemonMapper;
	}

	/**
	 * Not transactional on purpose: the PokeAPI call (including retries) must not hold a database
	 * connection. Repository calls run in their own short transactions.
	 */
	@Cacheable(cacheNames = CacheConfig.POKEMON_CACHE, key = "#nameOrId.trim().toLowerCase()")
	public PokemonResponse getPokemon(String nameOrId) {
		String key = nameOrId.trim().toLowerCase(Locale.ROOT);
		Pokemon pokemon = findStored(key).orElseGet(() -> fetchAndStore(key));
		return pokemonMapper.toResponse(pokemon);
	}

	@Transactional(readOnly = true)
	public Page<PokemonResponse> listStored(Pageable pageable) {
		return pokemonRepository.findAll(pageable).map(pokemonMapper::toResponse);
	}

	private Optional<Pokemon> findStored(String key) {
		return isNumeric(key)
				? pokemonRepository.findByPokeApiId(Integer.valueOf(key))
				: pokemonRepository.findByNameIgnoreCase(key);
	}

	private Pokemon fetchAndStore(String key) {
		log.info("Pokemon '{}' not stored locally, fetching from PokeAPI", key);
		Pokemon pokemon = pokemonMapper.toEntity(pokeApiClient.fetchPokemon(key));
		return pokemonRepository.save(pokemon);
	}

	private static boolean isNumeric(String value) {
		return !value.isEmpty() && value.length() < 10 && value.chars().allMatch(Character::isDigit);
	}

}
