package com.ballastlane.poke.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ballastlane.poke.support.AbstractApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Uses its own context with a tiny, non-refreshing quota so the rate limiter can be tested deterministically.
 */
@TestPropertySource(properties = {
		"resilience4j.ratelimiter.instances.pokeapi.limit-for-period=6",
		"resilience4j.ratelimiter.instances.pokeapi.limit-refresh-period=1h",
		"resilience4j.ratelimiter.instances.pokeapi.timeout-duration=0s" })
class PokeApiRateLimiterIntegrationTest extends AbstractApiIntegrationTest {

	@Test
	void rejectsUpstreamCallsOnceQuotaIsExhausted() throws Exception {
		mockMvc.perform(get("/api/v1/pokemon/pikachu")).andExpect(status().isOk());
		mockMvc.perform(get("/api/v1/pokemon/1")).andExpect(status().isOk());

		mockMvc.perform(get("/api/v1/pokemon/missingno"))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.title").value("Too many requests"));
		assertThat(pokeApi.countRequests("/api/v2/pokemon/missingno")).isZero();

		// Cached pokemon never touch PokeAPI, so they are not affected by the quota.
		mockMvc.perform(get("/api/v1/pokemon/pikachu")).andExpect(status().isOk());
	}

}
