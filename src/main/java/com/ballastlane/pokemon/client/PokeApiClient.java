package com.ballastlane.pokemon.client;

import com.ballastlane.pokemon.client.dto.PokeApiEvolutionChain;
import com.ballastlane.pokemon.client.dto.PokeApiPokemon;
import com.ballastlane.pokemon.client.dto.PokeApiPokemonList;
import com.ballastlane.pokemon.client.dto.PokeApiSpecies;
import com.ballastlane.pokemon.exception.PokeApiException;
import com.ballastlane.pokemon.exception.PokemonNotFoundException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Thin HTTP client for <a href="https://pokeapi.co">PokeAPI</a>.
 * <p>
 * Calls are protected by Resilience4j (instance {@value #RESILIENCE_INSTANCE}, configured under
 * {@code resilience4j.*} in {@code application.yml}). Aspects apply in the order
 * Retry → CircuitBreaker → RateLimiter → HTTP call, so every retry attempt is recorded by the circuit
 * breaker and consumes a rate-limiter permit.
 */
@Component
public class PokeApiClient {

	public static final String RESILIENCE_INSTANCE = "pokeapi";

	private static final Logger log = LoggerFactory.getLogger(PokeApiClient.class);

	private final RestClient restClient;

	public PokeApiClient(RestClient pokeApiRestClient) {
		this.restClient = pokeApiRestClient;
	}

	@Retry(name = RESILIENCE_INSTANCE)
	@CircuitBreaker(name = RESILIENCE_INSTANCE)
	@RateLimiter(name = RESILIENCE_INSTANCE)
	public PokeApiPokemon fetchPokemon(String nameOrId) {
		log.debug("Fetching pokemon '{}' from PokeAPI", nameOrId);
		return get(PokeApiPokemon.class, "pokemon '%s'".formatted(nameOrId),
				() -> new PokemonNotFoundException(nameOrId), "/pokemon/{nameOrId}", nameOrId);
	}

	/**
	 * Fetches a pokemon species (flavor texts and the link to its evolution chain).
	 */
	@Retry(name = RESILIENCE_INSTANCE)
	@CircuitBreaker(name = RESILIENCE_INSTANCE)
	@RateLimiter(name = RESILIENCE_INSTANCE)
	public PokeApiSpecies fetchSpecies(int speciesId) {
		log.debug("Fetching pokemon species {} from PokeAPI", speciesId);
		return get(PokeApiSpecies.class, "pokemon species %d".formatted(speciesId), null,
				"/pokemon-species/{id}", speciesId);
	}

	/**
	 * Fetches an evolution chain tree.
	 */
	@Retry(name = RESILIENCE_INSTANCE)
	@CircuitBreaker(name = RESILIENCE_INSTANCE)
	@RateLimiter(name = RESILIENCE_INSTANCE)
	public PokeApiEvolutionChain fetchEvolutionChain(int chainId) {
		log.debug("Fetching evolution chain {} from PokeAPI", chainId);
		return get(PokeApiEvolutionChain.class, "evolution chain %d".formatted(chainId), null,
				"/evolution-chain/{id}", chainId);
	}

	/**
	 * Fetches one page of the PokeAPI catalogue (names, detail URLs and the total count), ordered by id.
	 */
	@Retry(name = RESILIENCE_INSTANCE)
	@CircuitBreaker(name = RESILIENCE_INSTANCE)
	@RateLimiter(name = RESILIENCE_INSTANCE)
	public PokeApiPokemonList fetchPokemonPage(int offset, int limit) {
		log.debug("Fetching pokemon catalogue page offset={} limit={} from PokeAPI", offset, limit);
		return get(PokeApiPokemonList.class, "pokemon catalogue (offset=%d, limit=%d)".formatted(offset, limit),
				null, "/pokemon?offset={offset}&limit={limit}", offset, limit);
	}

	/**
	 * Performs a GET and maps failures: a 404 raises {@code notFound} when given (otherwise it is treated like any
	 * other error), and every other client error becomes a {@link PokeApiException}.
	 */
	private <T> T get(Class<T> type, String description, Supplier<? extends RuntimeException> notFound, String uri,
			Object... uriVariables) {
		try {
			RestClient.ResponseSpec response = restClient.get().uri(uri, uriVariables).retrieve();
			if (notFound != null) {
				response = response.onStatus(status -> status.value() == HttpStatus.NOT_FOUND.value(),
						(request, res) -> {
							throw notFound.get();
						});
			}
			return response.body(type);
		}
		catch (RestClientException ex) {
			throw new PokeApiException("Failed to fetch %s from PokeAPI".formatted(description), ex);
		}
	}

}
