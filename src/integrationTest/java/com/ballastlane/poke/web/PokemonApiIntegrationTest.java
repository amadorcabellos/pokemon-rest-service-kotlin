package com.ballastlane.poke.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ballastlane.poke.support.AbstractApiIntegrationTest;
import com.ballastlane.poke.web.dto.PokemonResponse;
import org.junit.jupiter.api.Test;

/**
 * End-to-end flow through the HTTP layer, PostgreSQL, Redis and a stubbed PokeAPI.
 */
class PokemonApiIntegrationTest extends AbstractApiIntegrationTest {

	@Test
	void fetchesFromPokeApiThenServesFromCache() throws Exception {
		mockMvc.perform(get("/api/v1/pokemon/pikachu"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(25))
				.andExpect(jsonPath("$.name").value("pikachu"))
				.andExpect(jsonPath("$.types[0]").value("electric"));

		assertThat(repository.findByNameIgnoreCase("pikachu")).isPresent();
		assertThat(pokemonCache().get("pikachu", PokemonResponse.class))
				.extracting(PokemonResponse::name)
				.isEqualTo("pikachu");

		mockMvc.perform(get("/api/v1/pokemon/PIKACHU")).andExpect(status().isOk());

		assertThat(pokeApi.countRequests("/api/v2/pokemon/pikachu")).isEqualTo(1);
	}

	@Test
	void servesFromDatabaseWhenCacheIsEmpty() throws Exception {
		mockMvc.perform(get("/api/v1/pokemon/1")).andExpect(status().isOk());
		pokemonCache().clear();

		mockMvc.perform(get("/api/v1/pokemon/1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("bulbasaur"))
				.andExpect(jsonPath("$.types[0]").value("grass"))
				.andExpect(jsonPath("$.types[1]").value("poison"));

		assertThat(pokeApi.countRequests("/api/v2/pokemon/1")).isEqualTo(1);
	}

	@Test
	void returns404WhenPokemonDoesNotExist() throws Exception {
		mockMvc.perform(get("/api/v1/pokemon/missingno"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.title").value("Pokemon not found"));

		assertThat(repository.count()).isZero();
	}

	@Test
	void returns502WhenPokeApiFails() throws Exception {
		mockMvc.perform(get("/api/v1/pokemon/glitch"))
				.andExpect(status().isBadGateway());
	}

	@Test
	void listsStoredPokemon() throws Exception {
		mockMvc.perform(get("/api/v1/pokemon/pikachu")).andExpect(status().isOk());
		mockMvc.perform(get("/api/v1/pokemon/1")).andExpect(status().isOk());

		mockMvc.perform(get("/api/v1/pokemon"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(2))
				.andExpect(jsonPath("$.content[0].name").value("bulbasaur"))
				.andExpect(jsonPath("$.content[1].name").value("pikachu"));
	}

	@Test
	void healthEndpointIsUp() throws Exception {
		mockMvc.perform(get("/actuator/health"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"));
	}

}
