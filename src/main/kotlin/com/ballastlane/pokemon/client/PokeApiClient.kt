package com.ballastlane.pokemon.client

import com.ballastlane.pokemon.client.dto.PokeApiEvolutionChain
import com.ballastlane.pokemon.client.dto.PokeApiPokemon
import com.ballastlane.pokemon.client.dto.PokeApiPokemonList
import com.ballastlane.pokemon.client.dto.PokeApiSpecies
import com.ballastlane.pokemon.exception.PokeApiException
import com.ballastlane.pokemon.exception.PokemonNotFoundException
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import io.github.resilience4j.ratelimiter.annotation.RateLimiter
import io.github.resilience4j.retry.annotation.Retry
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException

/**
 * Thin HTTP client for [PokeAPI](https://pokeapi.co).
 *
 * Calls are protected by Resilience4j (instance [RESILIENCE_INSTANCE], configured under `resilience4j.*` in
 * `application.yml`). Aspects apply in the order Retry → CircuitBreaker → RateLimiter → HTTP call, so every retry
 * attempt is recorded by the circuit breaker and consumes a rate-limiter permit.
 */
@Component
class PokeApiClient(private val pokeApiRestClient: RestClient) {

    @Retry(name = RESILIENCE_INSTANCE)
    @CircuitBreaker(name = RESILIENCE_INSTANCE)
    @RateLimiter(name = RESILIENCE_INSTANCE)
    fun fetchPokemon(nameOrId: String): PokeApiPokemon {
        log.debug("Fetching pokemon '{}' from PokeAPI", nameOrId)
        return get("pokemon '$nameOrId'", { PokemonNotFoundException(nameOrId) }, "/pokemon/{nameOrId}", nameOrId)
    }

    /**
     * Fetches a pokemon species (flavor texts and the link to its evolution chain).
     */
    @Retry(name = RESILIENCE_INSTANCE)
    @CircuitBreaker(name = RESILIENCE_INSTANCE)
    @RateLimiter(name = RESILIENCE_INSTANCE)
    fun fetchSpecies(speciesId: Int): PokeApiSpecies {
        log.debug("Fetching pokemon species {} from PokeAPI", speciesId)
        return get("pokemon species $speciesId", null, "/pokemon-species/{id}", speciesId)
    }

    /**
     * Fetches an evolution chain tree.
     */
    @Retry(name = RESILIENCE_INSTANCE)
    @CircuitBreaker(name = RESILIENCE_INSTANCE)
    @RateLimiter(name = RESILIENCE_INSTANCE)
    fun fetchEvolutionChain(chainId: Int): PokeApiEvolutionChain {
        log.debug("Fetching evolution chain {} from PokeAPI", chainId)
        return get("evolution chain $chainId", null, "/evolution-chain/{id}", chainId)
    }

    /**
     * Fetches one page of the PokeAPI catalogue (names, detail URLs and the total count), ordered by id.
     */
    @Retry(name = RESILIENCE_INSTANCE)
    @CircuitBreaker(name = RESILIENCE_INSTANCE)
    @RateLimiter(name = RESILIENCE_INSTANCE)
    fun fetchPokemonPage(offset: Int, limit: Int): PokeApiPokemonList {
        log.debug("Fetching pokemon catalogue page offset={} limit={} from PokeAPI", offset, limit)
        return get(
            "pokemon catalogue (offset=$offset, limit=$limit)", null,
            "/pokemon?offset={offset}&limit={limit}", offset, limit,
        )
    }

    /**
     * Performs a GET and maps failures: a 404 raises `notFound` when given (otherwise it is treated like any other
     * error), and every other client error becomes a [PokeApiException].
     */
    private inline fun <reified T : Any> get(
        description: String,
        noinline notFound: (() -> RuntimeException)?,
        uri: String,
        vararg uriVariables: Any,
    ): T {
        val body = try {
            var response = pokeApiRestClient.get().uri(uri, *uriVariables).retrieve()
            if (notFound != null) {
                response = response.onStatus({ it.value() == HttpStatus.NOT_FOUND.value() }) { _, _ ->
                    throw notFound()
                }
            }
            response.body(T::class.java)
        } catch (ex: RestClientException) {
            throw PokeApiException("Failed to fetch $description from PokeAPI", ex)
        }
        return checkNotNull(body) { "PokeAPI returned no body for $description" }
    }

    companion object {

        const val RESILIENCE_INSTANCE = "pokeapi"

        private val log = LoggerFactory.getLogger(PokeApiClient::class.java)
    }
}
