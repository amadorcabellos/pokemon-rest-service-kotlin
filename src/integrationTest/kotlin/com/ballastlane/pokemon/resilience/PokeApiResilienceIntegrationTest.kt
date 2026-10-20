package com.ballastlane.pokemon.resilience

import com.ballastlane.pokemon.support.AbstractApiIntegrationTest
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.get

/**
 * Verifies retry and circuit breaker behaviour against the WireMock PokeAPI stub. Thresholds come from
 * `application-integration.yml`: 3 attempts, circuit opens after 4 recorded calls with at least 50% failures.
 */
class PokeApiResilienceIntegrationTest : AbstractApiIntegrationTest() {

    @Test
    fun retriesUpstreamServerErrors() {
        mockMvc.get("/api/v1/pokemon/glitch").andExpect { status { isBadGateway() } }

        pokeApi.countRequests("/api/v2/pokemon/glitch") shouldBe 3
    }

    @Test
    fun doesNotRetryOrTripCircuitOnNotFound() {
        repeat(5) {
            mockMvc.get("/api/v1/pokemon/missingno").andExpect { status { isNotFound() } }
        }

        pokeApi.countRequests("/api/v2/pokemon/missingno") shouldBe 5
        pokeApiCircuitBreaker().state shouldBe CircuitBreaker.State.CLOSED
    }

    @Test
    fun opensCircuitAfterRepeatedFailuresAndShortCircuitsCalls() {
        // 1st request: 3 failed attempts, below the 4-call minimum -> still closed.
        mockMvc.get("/api/v1/pokemon/glitch").andExpect { status { isBadGateway() } }
        pokeApiCircuitBreaker().state shouldBe CircuitBreaker.State.CLOSED

        // 2nd request: 4th failure opens the circuit, the next attempt is rejected without calling PokeAPI.
        mockMvc.get("/api/v1/pokemon/glitch").andExpect {
            status { isServiceUnavailable() }
            jsonPath("$.title") { value("Service temporarily unavailable") }
        }
        pokeApiCircuitBreaker().state shouldBe CircuitBreaker.State.OPEN
        pokeApi.countRequests("/api/v2/pokemon/glitch") shouldBe 4

        // While open, even healthy lookups are short-circuited.
        mockMvc.get("/api/v1/pokemon/pikachu").andExpect { status { isServiceUnavailable() } }
        pokeApi.countRequests("/api/v2/pokemon/pikachu") shouldBe 0

        mockMvc.get("/actuator/circuitbreakers").andExpect {
            status { isOk() }
            jsonPath("$.circuitBreakers.pokeapi.state") { value("OPEN") }
        }
    }

    @Test
    fun openCircuitDoesNotAffectCachedPokemon() {
        mockMvc.get("/api/v1/pokemon/pikachu").andExpect { status { isOk() } }
        pokeApiCircuitBreaker().transitionToOpenState()

        mockMvc.get("/api/v1/pokemon/pikachu").andExpect {
            status { isOk() }
            jsonPath("$.name") { value("pikachu") }
        }
    }
}
