package com.ballastlane.pokemon.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ballastlane.pokemon.support.AbstractApiIntegrationTest;
import com.ballastlane.pokemon.web.dto.PokemonDetails;
import org.junit.jupiter.api.Test;

/**
 * Detailed pokemon lookup end to end: HTTP layer, PokeAPI (WireMock: pokemon → species → evolution chain),
 * Redis caching and recording in PostgreSQL.
 */
class PokemonApiIntegrationTest extends AbstractApiIntegrationTest {

	@Test
	void returnsDetailedView() throws Exception {
		mockMvc.perform(get("/api/v1/pokemon/pikachu"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(25))
				.andExpect(jsonPath("$.name").value("pikachu"))
				.andExpect(jsonPath("$.imageUrl").value(
						"https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/other/official-artwork/25.png"))
				.andExpect(jsonPath("$.stats.length()").value(6))
				.andExpect(jsonPath("$.stats[0].name").value("hp"))
				.andExpect(jsonPath("$.stats[0].value").value(35))
				.andExpect(jsonPath("$.stats[5].name").value("speed"))
				.andExpect(jsonPath("$.stats[5].value").value(90))
				.andExpect(jsonPath("$.totalStats").value(320))
				.andExpect(jsonPath("$.description").value("Pikachu that can generate powerful electricity have cheek "
						+ "sacs that are extra soft and super stretchy."))
				.andExpect(jsonPath("$.evolution.name").value("pichu"))
				.andExpect(jsonPath("$.evolution.current").value(false))
				.andExpect(jsonPath("$.evolution.evolvesTo[0].name").value("pikachu"))
				.andExpect(jsonPath("$.evolution.evolvesTo[0].evolvesVia").value("friendship"))
				.andExpect(jsonPath("$.evolution.evolvesTo[0].current").value(true))
				.andExpect(jsonPath("$.evolution.evolvesTo[0].evolvesTo[0].name").value("raichu"))
				.andExpect(jsonPath("$.evolution.evolvesTo[0].evolvesTo[0].evolvesVia").value("use thunder-stone"))
				.andExpect(jsonPath("$.evolution.evolvesTo[0].evolvesTo[0].evolvesTo").isEmpty())
				.andExpect(jsonPath("$.localData.localizedNames").isEmpty())
				.andExpect(jsonPath("$.localData.geoMetadata").isEmpty())
				.andExpect(jsonPath("$.localData.tags").isEmpty())
				.andExpect(jsonPath("$.localData.modifiedAt").isEmpty())
				.andExpect(jsonPath("$.localData.version").value(0))
				.andExpect(header().string("ETag", "\"0\""));
	}

	@Test
	void servesRepeatedLookupFromCache() throws Exception {
		mockMvc.perform(get("/api/v1/pokemon/pikachu")).andExpect(status().isOk());
		assertThat(pokemonDetailsCache().get("pikachu", PokemonDetails.class)).satisfies(cached -> {
			assertThat(cached.name()).isEqualTo("pikachu");
			// Local data is read fresh on every request, never cached.
			assertThat(cached.localData()).isNull();
		});

		mockMvc.perform(get("/api/v1/pokemon/PIKACHU")).andExpect(status().isOk());

		assertThat(pokeApi.countRequests("/api/v2/pokemon/pikachu")).isEqualTo(1);
		assertThat(pokeApi.countRequests("/api/v2/pokemon-species/25")).isEqualTo(1);
		assertThat(pokeApi.countRequests("/api/v2/evolution-chain/10")).isEqualTo(1);
	}

	@Test
	void familyMembersShareOneEvolutionChainRequest() throws Exception {
		mockMvc.perform(get("/api/v1/pokemon/pichu"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.evolution.name").value("pichu"))
				.andExpect(jsonPath("$.evolution.current").value(true))
				.andExpect(jsonPath("$.evolution.evolvesTo[0].current").value(false));
		mockMvc.perform(get("/api/v1/pokemon/pikachu"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.evolution.current").value(false))
				.andExpect(jsonPath("$.evolution.evolvesTo[0].current").value(true));

		assertThat(pokeApi.countRequests("/api/v2/evolution-chain/10")).isEqualTo(1);
	}

	@Test
	void refetchesFromPokeApiWhenViewIsNoLongerCached() throws Exception {
		mockMvc.perform(get("/api/v1/pokemon/pikachu")).andExpect(status().isOk());
		pokemonDetailsCache().invalidate();

		mockMvc.perform(get("/api/v1/pokemon/pikachu")).andExpect(status().isOk());

		assertThat(pokeApi.countRequests("/api/v2/pokemon/pikachu")).isEqualTo(2);
		assertThat(pokeApi.countRequests("/api/v2/evolution-chain/10")).isEqualTo(1);
	}

	@Test
	void recordsLookedUpPokemonInPostgresOnce() throws Exception {
		mockMvc.perform(get("/api/v1/pokemon/pikachu")).andExpect(status().isOk());
		mockMvc.perform(get("/api/v1/pokemon/25")).andExpect(status().isOk());

		assertThat(repository.count()).isEqualTo(1);
		assertThat(repository.findByPokeApiId(25))
				.hasValueSatisfying(pokemon -> assertThat(pokemon.getTypes()).containsExactly("electric"));
	}

	@Test
	void servesCachedViewWhileCircuitIsOpen() throws Exception {
		mockMvc.perform(get("/api/v1/pokemon/pikachu")).andExpect(status().isOk());
		pokeApiCircuitBreaker().transitionToOpenState();

		mockMvc.perform(get("/api/v1/pokemon/pikachu"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalStats").value(320));
	}

	@Test
	void returns404WhenPokemonDoesNotExist() throws Exception {
		mockMvc.perform(get("/api/v1/pokemon/missingno"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.title").value("Pokemon not found"));

		assertThat(repository.count()).isZero();
	}

	@Test
	void returns400ForInvalidName() throws Exception {
		mockMvc.perform(get("/api/v1/pokemon/pika_chu!")).andExpect(status().isBadRequest());
	}

	@Test
	void failsWholeLookupWithoutCachingWhenSpeciesFails() throws Exception {
		mockMvc.perform(get("/api/v1/pokemon/broken")).andExpect(status().isBadGateway());

		assertThat(pokeApi.countRequests("/api/v2/pokemon-species/9999")).isEqualTo(3);
		assertThat(pokemonDetailsCache().get("broken")).isNull();
	}

	@Test
	void returns502WhenPokeApiFails() throws Exception {
		mockMvc.perform(get("/api/v1/pokemon/glitch"))
				.andExpect(status().isBadGateway());
	}

	@Test
	void healthEndpointIsUp() throws Exception {
		mockMvc.perform(get("/actuator/health"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"));
	}

}
