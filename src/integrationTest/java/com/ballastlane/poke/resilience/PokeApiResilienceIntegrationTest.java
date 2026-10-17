package com.ballastlane.poke.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ballastlane.poke.support.AbstractApiIntegrationTest;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;

/**
 * Verifies retry and circuit breaker behaviour against the WireMock PokeAPI stub.
 * Thresholds come from {@code application-integration.yml}: 3 attempts, circuit opens after 4 recorded calls
 * with at least 50% failures.
 */
class PokeApiResilienceIntegrationTest extends AbstractApiIntegrationTest {

	@Test
	void retriesUpstreamServerErrors() throws Exception {
		mockMvc.perform(get("/api/v1/pokemon/glitch")).andExpect(status().isBadGateway());

		assertThat(pokeApi.countRequests("/api/v2/pokemon/glitch")).isEqualTo(3);
	}

	@Test
	void doesNotRetryOrTripCircuitOnNotFound() throws Exception {
		for (int i = 0; i < 5; i++) {
			mockMvc.perform(get("/api/v1/pokemon/missingno")).andExpect(status().isNotFound());
		}

		assertThat(pokeApi.countRequests("/api/v2/pokemon/missingno")).isEqualTo(5);
		assertThat(pokeApiCircuitBreaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
	}

	@Test
	void opensCircuitAfterRepeatedFailuresAndShortCircuitsCalls() throws Exception {
		// 1st request: 3 failed attempts, below the 4-call minimum -> still closed.
		mockMvc.perform(get("/api/v1/pokemon/glitch")).andExpect(status().isBadGateway());
		assertThat(pokeApiCircuitBreaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);

		// 2nd request: 4th failure opens the circuit, the next attempt is rejected without calling PokeAPI.
		mockMvc.perform(get("/api/v1/pokemon/glitch"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.title").value("Service temporarily unavailable"));
		assertThat(pokeApiCircuitBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
		assertThat(pokeApi.countRequests("/api/v2/pokemon/glitch")).isEqualTo(4);

		// While open, even healthy lookups are short-circuited.
		mockMvc.perform(get("/api/v1/pokemon/pikachu")).andExpect(status().isServiceUnavailable());
		assertThat(pokeApi.countRequests("/api/v2/pokemon/pikachu")).isZero();

		mockMvc.perform(get("/actuator/circuitbreakers"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.circuitBreakers.pokeapi.state").value("OPEN"));
	}

	@Test
	void openCircuitDoesNotAffectCachedPokemon() throws Exception {
		mockMvc.perform(get("/api/v1/pokemon/pikachu")).andExpect(status().isOk());
		pokeApiCircuitBreaker().transitionToOpenState();

		mockMvc.perform(get("/api/v1/pokemon/pikachu"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("pikachu"));
	}

}
