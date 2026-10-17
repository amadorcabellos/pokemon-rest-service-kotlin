package com.ballastlane.poke.client;

import com.ballastlane.poke.client.dto.PokeApiPokemon;
import com.ballastlane.poke.client.dto.PokeApiPokemonList;
import com.ballastlane.poke.exception.PokeApiException;
import com.ballastlane.poke.exception.PokemonNotFoundException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
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
		try {
			return restClient.get()
					.uri("/pokemon/{nameOrId}", nameOrId)
					.retrieve()
					.onStatus(status -> status.value() == HttpStatus.NOT_FOUND.value(), (request, response) -> {
						throw new PokemonNotFoundException(nameOrId);
					})
					.body(PokeApiPokemon.class);
		}
		catch (PokemonNotFoundException ex) {
			throw ex;
		}
		catch (RestClientException ex) {
			throw new PokeApiException("Failed to fetch pokemon '%s' from PokeAPI".formatted(nameOrId), ex);
		}
	}

	/**
	 * Fetches one page of the PokeAPI catalogue (names, detail URLs and the total count), ordered by id.
	 */
	@Retry(name = RESILIENCE_INSTANCE)
	@CircuitBreaker(name = RESILIENCE_INSTANCE)
	@RateLimiter(name = RESILIENCE_INSTANCE)
	public PokeApiPokemonList fetchPokemonPage(int offset, int limit) {
		log.debug("Fetching pokemon catalogue page offset={} limit={} from PokeAPI", offset, limit);
		try {
			return restClient.get()
					.uri("/pokemon?offset={offset}&limit={limit}", offset, limit)
					.retrieve()
					.body(PokeApiPokemonList.class);
		}
		catch (RestClientException ex) {
			throw new PokeApiException(
					"Failed to fetch pokemon catalogue (offset=%d, limit=%d) from PokeAPI".formatted(offset, limit), ex);
		}
	}

}
