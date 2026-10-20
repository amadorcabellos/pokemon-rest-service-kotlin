package com.ballastlane.pokemon.service

import com.ballastlane.pokemon.client.PokeApiClient
import com.ballastlane.pokemon.client.PokeApiUrls
import com.ballastlane.pokemon.client.dto.PokeApiPokemon
import com.ballastlane.pokemon.config.CacheConfig
import com.ballastlane.pokemon.mapper.PokemonDetailsMapper
import com.ballastlane.pokemon.mapper.PokemonMapper
import com.ballastlane.pokemon.repository.PokemonRepository
import com.ballastlane.pokemon.text.javaTrim
import com.ballastlane.pokemon.web.dto.PokemonDetails
import java.util.Locale
import org.slf4j.LoggerFactory
import org.springframework.cache.annotation.Cacheable
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service

/**
 * Builds the detailed view of a pokemon from PokeAPI (pokemon → species → evolution chain) and records the basic
 * pokemon in PostgreSQL the first time it is looked up.
 */
@Service
class PokemonDetailsService(
    private val pokeApiClient: PokeApiClient,
    private val evolutionChainLoader: EvolutionChainLoader,
    private val pokemonRepository: PokemonRepository,
    private val pokemonMapper: PokemonMapper,
    private val detailsMapper: PokemonDetailsMapper,
) {

    /**
     * Not transactional on purpose: PokeAPI calls (including retries) must not hold a database connection. The save
     * runs in its own short transaction. If any PokeAPI call fails, nothing is cached.
     */
    @Cacheable(cacheNames = [CacheConfig.POKEMON_DETAILS_CACHE], key = "#nameOrId.trim().toLowerCase()", sync = true)
    fun getPokemon(nameOrId: String): PokemonDetails {
        val key = nameOrId.javaTrim().lowercase(Locale.ROOT)
        val pokemon = pokeApiClient.fetchPokemon(key)
        recordIfNew(pokemon)

        // The species (not the pokemon name) links description and evolution, so alternate forms resolve correctly.
        val species = pokeApiClient.fetchSpecies(PokeApiUrls.idFrom(checkNotNull(pokemon.species?.url)))
        val chain = species.evolutionChain
        val evolution = if (chain != null) {
            evolutionChainLoader.load(PokeApiUrls.idFrom(checkNotNull(chain.url)))
        } else {
            detailsMapper.singleStage(species)
        }

        return detailsMapper.toDetails(pokemon, species, evolution)
    }

    private fun recordIfNew(pokemon: PokeApiPokemon) {
        if (pokemonRepository.findByPokeApiId(pokemon.id).isPresent) {
            return
        }
        try {
            pokemonRepository.save(pokemonMapper.toEntity(pokemon))
        } catch (ex: DataIntegrityViolationException) {
            // A concurrent first lookup stored it already.
            log.debug("Pokemon {} was stored concurrently", pokemon.id)
        }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(PokemonDetailsService::class.java)
    }
}
