package com.ballastlane.pokemon.resilience

import com.ballastlane.pokemon.support.AbstractApiIntegrationTest
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.get

/**
 * Uses its own context with a tiny, non-refreshing quota so the rate limiter can be tested deterministically.
 */
@TestPropertySource(
    properties = [
        "resilience4j.ratelimiter.instances.pokeapi.limit-for-period=6",
        "resilience4j.ratelimiter.instances.pokeapi.limit-refresh-period=1h",
        "resilience4j.ratelimiter.instances.pokeapi.timeout-duration=0s",
    ],
)
class PokeApiRateLimiterIntegrationTest : AbstractApiIntegrationTest() {

    @Test
    fun rejectsUpstreamCallsOnceQuotaIsExhausted() {
        mockMvc.get("/api/v1/pokemon/pikachu").andExpect { status { isOk() } }
        mockMvc.get("/api/v1/pokemon/1").andExpect { status { isOk() } }

        mockMvc.get("/api/v1/pokemon/missingno").andExpect {
            status { isTooManyRequests() }
            jsonPath("$.title") { value("Too many requests") }
        }
        pokeApi.countRequests("/api/v2/pokemon/missingno") shouldBe 0

        // Cached pokemon never touch PokeAPI, so they are not affected by the quota.
        mockMvc.get("/api/v1/pokemon/pikachu").andExpect { status { isOk() } }
    }
}
