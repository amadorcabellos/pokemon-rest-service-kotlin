package com.ballastlane.poke.support;

import com.ballastlane.poke.client.PokeApiClient;
import com.ballastlane.poke.config.CacheConfig;
import com.ballastlane.poke.repository.PokemonRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Base class for full-stack API tests: HTTP layer, PostgreSQL, Redis and a WireMock-backed PokeAPI.
 * Subclasses share one Spring context (and one set of containers) unless they change its configuration.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@Import(IntegrationTestConfiguration.class)
public abstract class AbstractApiIntegrationTest {

	@Autowired
	protected MockMvc mockMvc;

	@Autowired
	protected PokemonRepository repository;

	@Autowired
	protected CacheManager cacheManager;

	@Autowired
	protected WireMockContainer pokeApi;

	@Autowired
	protected CircuitBreakerRegistry circuitBreakerRegistry;

	@BeforeEach
	void resetState() {
		repository.deleteAll();
		cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).invalidate());
		pokeApi.resetRequests();
		pokeApiCircuitBreaker().reset();
	}

	protected Cache pokemonDetailsCache() {
		return cacheManager.getCache(CacheConfig.POKEMON_DETAILS_CACHE);
	}

	protected CircuitBreaker pokeApiCircuitBreaker() {
		return circuitBreakerRegistry.circuitBreaker(PokeApiClient.RESILIENCE_INSTANCE);
	}

}
