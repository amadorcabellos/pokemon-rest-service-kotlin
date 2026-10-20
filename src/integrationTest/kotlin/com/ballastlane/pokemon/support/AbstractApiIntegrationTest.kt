package com.ballastlane.pokemon.support

import com.ballastlane.pokemon.auth.LoginAttemptLimiter
import com.ballastlane.pokemon.auth.TokenService
import com.ballastlane.pokemon.client.PokeApiClient
import com.ballastlane.pokemon.config.CacheConfig
import com.ballastlane.pokemon.domain.AppUser
import com.ballastlane.pokemon.domain.AuthSession
import com.ballastlane.pokemon.domain.Role
import com.ballastlane.pokemon.repository.AppUserRepository
import com.ballastlane.pokemon.repository.AuthSessionRepository
import com.ballastlane.pokemon.repository.PokemonRepository
import com.ballastlane.pokemon.repository.SyncRunRepository
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import java.time.Clock
import java.time.Duration
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.cache.Cache
import org.springframework.cache.CacheManager
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.transaction.support.TransactionTemplate

/**
 * Base class for full-stack API tests: HTTP layer, PostgreSQL, Redis and a WireMock-backed PokeAPI.
 * Subclasses share one Spring context (and one set of containers) unless they change its configuration.
 *
 * Field injection is used here (and only here) so that subclasses keep their own constructor free for the beans
 * they need.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@Import(IntegrationTestConfiguration::class)
abstract class AbstractApiIntegrationTest {

    @Autowired
    protected lateinit var mockMvc: MockMvc

    @Autowired
    protected lateinit var repository: PokemonRepository

    @Autowired
    protected lateinit var syncRunRepository: SyncRunRepository

    @Autowired
    protected lateinit var cacheManager: CacheManager

    @Autowired
    protected lateinit var pokeApi: WireMockContainer

    @Autowired
    protected lateinit var circuitBreakerRegistry: CircuitBreakerRegistry

    @Autowired
    protected lateinit var userRepository: AppUserRepository

    @Autowired
    private lateinit var sessionRepository: AuthSessionRepository

    @Autowired
    private lateinit var tokenService: TokenService

    @Autowired
    private lateinit var loginAttemptLimiter: LoginAttemptLimiter

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    @Autowired
    private lateinit var clock: Clock

    @BeforeEach
    fun resetState() {
        // Proprietary rows go with their pokemon, run failures with their run, and sessions and refresh tokens with
        // their user (ON DELETE CASCADE).
        repository.deleteAll()
        syncRunRepository.deleteAllInBatch()
        userRepository.deleteAll()
        loginAttemptLimiter.reset()
        cacheManager.cacheNames.forEach { cacheManager.getCache(it)?.invalidate() }
        pokeApi.resetRequests()
        pokeApiCircuitBreaker().reset()
    }

    /**
     * An `Authorization` header value for a new user with the given roles and a fresh session. Skips the password
     * (and its deliberately slow hashing): tests of sign-in itself go through the API.
     */
    protected fun bearerTokenFor(username: String, vararg roles: Role): String = checkNotNull(
        transactionTemplate.execute {
            val user = userRepository.save(AppUser(username, "not-used", roles.toSet()))
            val now = clock.instant()
            val session = sessionRepository.save(AuthSession(user, now, now.plus(Duration.ofDays(1))))
            "Bearer " + tokenService.issue(session, user, now).accessToken
        },
    )

    protected fun pokemonDetailsCache(): Cache = checkNotNull(cacheManager.getCache(CacheConfig.POKEMON_DETAILS_CACHE))

    protected fun pokeApiCircuitBreaker(): CircuitBreaker =
        circuitBreakerRegistry.circuitBreaker(PokeApiClient.RESILIENCE_INSTANCE)
}
