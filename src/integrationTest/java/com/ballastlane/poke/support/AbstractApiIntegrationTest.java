package com.ballastlane.poke.support;

import com.ballastlane.poke.auth.LoginAttemptLimiter;
import com.ballastlane.poke.auth.TokenService;
import com.ballastlane.poke.client.PokeApiClient;
import com.ballastlane.poke.config.CacheConfig;
import com.ballastlane.poke.domain.AppUser;
import com.ballastlane.poke.domain.AuthSession;
import com.ballastlane.poke.domain.Role;
import com.ballastlane.poke.repository.AppUserRepository;
import com.ballastlane.poke.repository.AuthSessionRepository;
import com.ballastlane.poke.repository.PokemonRepository;
import com.ballastlane.poke.repository.SyncRunRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

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
	protected SyncRunRepository syncRunRepository;

	@Autowired
	protected CacheManager cacheManager;

	@Autowired
	protected WireMockContainer pokeApi;

	@Autowired
	protected CircuitBreakerRegistry circuitBreakerRegistry;

	@Autowired
	protected AppUserRepository userRepository;

	@Autowired
	private AuthSessionRepository sessionRepository;

	@Autowired
	private TokenService tokenService;

	@Autowired
	private LoginAttemptLimiter loginAttemptLimiter;

	@Autowired
	private TransactionTemplate transactionTemplate;

	@Autowired
	private Clock clock;

	@BeforeEach
	void resetState() {
		// Proprietary rows go with their pokemon, run failures with their run, and sessions and refresh tokens
		// with their user (ON DELETE CASCADE).
		repository.deleteAll();
		syncRunRepository.deleteAllInBatch();
		userRepository.deleteAll();
		loginAttemptLimiter.reset();
		cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).invalidate());
		pokeApi.resetRequests();
		pokeApiCircuitBreaker().reset();
	}

	/**
	 * An {@code Authorization} header value for a new user with the given roles and a fresh session. Skips the
	 * password (and its deliberately slow hashing): tests of sign-in itself go through the API.
	 */
	protected String bearerTokenFor(String username, Role... roles) {
		return transactionTemplate.execute(status -> {
			AppUser user = userRepository.save(new AppUser(username, "not-used", Set.of(roles)));
			Instant now = clock.instant();
			AuthSession session = sessionRepository.save(new AuthSession(user, now, now.plus(Duration.ofDays(1))));
			return "Bearer " + tokenService.issue(session, user, now).accessToken();
		});
	}

	protected Cache pokemonDetailsCache() {
		return cacheManager.getCache(CacheConfig.POKEMON_DETAILS_CACHE);
	}

	protected CircuitBreaker pokeApiCircuitBreaker() {
		return circuitBreakerRegistry.circuitBreaker(PokeApiClient.RESILIENCE_INSTANCE);
	}

}
