package com.ballastlane.pokemon.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ballastlane.pokemon.support.AbstractApiIntegrationTest;
import org.junit.jupiter.api.Test;

/**
 * Browsing the catalogue end to end. The WireMock PokeAPI stub exposes a 3-pokemon catalogue
 * (bulbasaur, ivysaur, venusaur); see {@code wiremock/mappings/catalogue-*.json}.
 */
class PokemonCatalogIntegrationTest extends AbstractApiIntegrationTest {

	private static final String CATALOGUE = "/api/v2/pokemon";

	@Test
	void returnsFirstPageWithEntriesAndMetadata() throws Exception {
		mockMvc.perform(get("/api/v1/pokemon").param("size", "2"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.page").value(0))
				.andExpect(jsonPath("$.size").value(2))
				.andExpect(jsonPath("$.totalElements").value(3))
				.andExpect(jsonPath("$.totalPages").value(2))
				.andExpect(jsonPath("$.content.length()").value(2))
				.andExpect(jsonPath("$.content[0].id").value(1))
				.andExpect(jsonPath("$.content[0].name").value("bulbasaur"))
				.andExpect(jsonPath("$.content[0].spriteUrl").value(
						"https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/1.png"))
				.andExpect(jsonPath("$.content[0].category[0]").value("grass"))
				.andExpect(jsonPath("$.content[0].category[1]").value("poison"))
				.andExpect(jsonPath("$.content[0].massKg").value(6.9))
				.andExpect(jsonPath("$.content[0].skills[0].name").value("overgrow"))
				.andExpect(jsonPath("$.content[0].skills[0].hidden").value(false))
				.andExpect(jsonPath("$.content[0].skills[1].name").value("chlorophyll"))
				.andExpect(jsonPath("$.content[0].skills[1].hidden").value(true))
				.andExpect(jsonPath("$.content[1].name").value("ivysaur"));
	}

	@Test
	void returnsPartialLastPage() throws Exception {
		mockMvc.perform(get("/api/v1/pokemon").param("page", "1").param("size", "2"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(1))
				.andExpect(jsonPath("$.content[0].name").value("venusaur"))
				.andExpect(jsonPath("$.content[0].massKg").value(100.0));
	}

	@Test
	void servesRepeatedPageFromCacheWithoutCallingPokeApi() throws Exception {
		mockMvc.perform(get("/api/v1/pokemon").param("size", "2")).andExpect(status().isOk());
		mockMvc.perform(get("/api/v1/pokemon").param("size", "2"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[1].name").value("ivysaur"));

		assertThat(pokeApi.countRequests(CATALOGUE)).isEqualTo(1);
		assertThat(pokeApi.countRequests("/api/v2/pokemon/1")).isEqualTo(1);
		assertThat(pokeApi.countRequests("/api/v2/pokemon/2")).isEqualTo(1);
	}

	@Test
	void reusesCachedEntriesAcrossPageSizes() throws Exception {
		mockMvc.perform(get("/api/v1/pokemon").param("size", "2")).andExpect(status().isOk());
		mockMvc.perform(get("/api/v1/pokemon").param("size", "3"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(3));

		assertThat(pokeApi.countRequests("/api/v2/pokemon/1")).isEqualTo(1);
		assertThat(pokeApi.countRequests("/api/v2/pokemon/2")).isEqualTo(1);
		assertThat(pokeApi.countRequests("/api/v2/pokemon/3")).isEqualTo(1);
	}

	@Test
	void servesCachedPageWhileCircuitIsOpen() throws Exception {
		mockMvc.perform(get("/api/v1/pokemon").param("size", "2")).andExpect(status().isOk());
		pokeApiCircuitBreaker().transitionToOpenState();

		mockMvc.perform(get("/api/v1/pokemon").param("size", "2"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[0].name").value("bulbasaur"));
	}

	@Test
	void returns503ForUncachedPageWhileCircuitIsOpen() throws Exception {
		pokeApiCircuitBreaker().transitionToOpenState();

		mockMvc.perform(get("/api/v1/pokemon").param("size", "2"))
				.andExpect(status().isServiceUnavailable());
		assertThat(pokeApi.countRequests(CATALOGUE)).isZero();
	}

	@Test
	void returnsEmptyPagePastTheEnd() throws Exception {
		mockMvc.perform(get("/api/v1/pokemon").param("page", "5").param("size", "2"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content").isEmpty())
				.andExpect(jsonPath("$.totalElements").value(3))
				.andExpect(jsonPath("$.totalPages").value(2));
	}

	@Test
	void rejectsInvalidPaging() throws Exception {
		mockMvc.perform(get("/api/v1/pokemon").param("size", "51"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value("'size' must be between 1 and 50"));
		mockMvc.perform(get("/api/v1/pokemon").param("page", "-1"))
				.andExpect(status().isBadRequest());

		assertThat(pokeApi.countRequests(CATALOGUE)).isZero();
	}

}
