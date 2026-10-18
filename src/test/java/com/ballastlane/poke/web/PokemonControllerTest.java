package com.ballastlane.poke.web;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ballastlane.poke.config.CatalogProperties;
import com.ballastlane.poke.exception.PokeApiException;
import com.ballastlane.poke.exception.PokemonNotFoundException;
import com.ballastlane.poke.localdata.LocalDataRequestParser;
import com.ballastlane.poke.localdata.LocalDataService;
import com.ballastlane.poke.service.PokemonCatalogService;
import com.ballastlane.poke.service.PokemonDetailsService;
import com.ballastlane.poke.web.dto.EvolutionStage;
import com.ballastlane.poke.web.dto.GeoEntry;
import com.ballastlane.poke.web.dto.LocalData;
import com.ballastlane.poke.web.dto.LocalizedName;
import com.ballastlane.poke.web.dto.PokemonDetails;
import com.ballastlane.poke.web.dto.Stat;
import com.ballastlane.poke.web.dto.PokemonSummary;
import com.ballastlane.poke.web.dto.PokemonSummaryPage;
import com.ballastlane.poke.web.dto.Skill;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PokemonController.class)
@Import({ LocalDataRequestParser.class, WebSecurityTestConfiguration.class })
class PokemonControllerTest {

	private static final PokemonDetails PIKACHU = new PokemonDetails(25, "pikachu", "artwork.png",
			List.of(new Stat("hp", 35), new Stat("speed", 90)), 125, "It stores electricity.",
			new EvolutionStage(172, "pichu", null, false, List.of(
					new EvolutionStage(25, "pikachu", "friendship", true, List.of(
							new EvolutionStage(26, "raichu", "use thunder-stone", false, List.of()))))));

	@Autowired
	private MockMvc mockMvc;

	private static final PokemonSummary BULBASAUR = new PokemonSummary(1, "bulbasaur", "sprite.png",
			List.of("grass", "poison"), new BigDecimal("6.9"),
			List.of(new Skill("overgrow", false), new Skill("chlorophyll", true)));

	@MockitoBean
	private PokemonDetailsService pokemonService;

	@MockitoBean
	private PokemonCatalogService pokemonCatalogService;

	@MockitoBean
	private LocalDataService localDataService;

	@TestConfiguration
	@EnableConfigurationProperties(CatalogProperties.class)
	static class CatalogPropertiesConfig {

	}

	@BeforeEach
	void noLocalData() {
		given(localDataService.read(anyInt())).willReturn(LocalData.EMPTY);
	}

	@Test
	void getPokemonReturnsPokemon() throws Exception {
		given(pokemonService.getPokemon("pikachu")).willReturn(PIKACHU);

		mockMvc.perform(get("/api/v1/pokemon/pikachu"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(25))
				.andExpect(jsonPath("$.name").value("pikachu"))
				.andExpect(jsonPath("$.imageUrl").value("artwork.png"))
				.andExpect(jsonPath("$.stats[0].name").value("hp"))
				.andExpect(jsonPath("$.stats[0].value").value(35))
				.andExpect(jsonPath("$.totalStats").value(125))
				.andExpect(jsonPath("$.description").value("It stores electricity."))
				.andExpect(jsonPath("$.evolution.name").value("pichu"))
				.andExpect(jsonPath("$.evolution.evolvesVia").doesNotExist())
				.andExpect(jsonPath("$.evolution.evolvesTo[0].name").value("pikachu"))
				.andExpect(jsonPath("$.evolution.evolvesTo[0].current").value(true))
				.andExpect(jsonPath("$.evolution.evolvesTo[0].evolvesTo[0].evolvesVia").value("use thunder-stone"))
				.andExpect(jsonPath("$.localData.tags").isEmpty())
				.andExpect(jsonPath("$.localData.version").value(0))
				.andExpect(header().string("ETag", "\"0\""));
	}

	@Test
	void getPokemonAddsTheCurrentLocalData() throws Exception {
		given(pokemonService.getPokemon("pikachu")).willReturn(PIKACHU);
		given(localDataService.read(25)).willReturn(new LocalData(List.of(new LocalizedName("ja-JP", "ピカチュウ")),
				List.of(new GeoEntry("region", "kanto", null)), List.of("mascot"),
				Instant.parse("2026-10-18T10:00:00Z"), 3));

		mockMvc.perform(get("/api/v1/pokemon/pikachu"))
				.andExpect(status().isOk())
				.andExpect(header().string("ETag", "\"3\""))
				.andExpect(jsonPath("$.name").value("pikachu"))
				.andExpect(jsonPath("$.localData.localizedNames[0].locale").value("ja-JP"))
				.andExpect(jsonPath("$.localData.localizedNames[0].name").value("ピカチュウ"))
				.andExpect(jsonPath("$.localData.geoMetadata[0].kind").value("region"))
				.andExpect(jsonPath("$.localData.tags[0]").value("mascot"))
				.andExpect(jsonPath("$.localData.modifiedAt").value("2026-10-18T10:00:00Z"))
				.andExpect(jsonPath("$.localData.version").value(3));
	}

	@Test
	void getPokemonReturns404WhenNotFound() throws Exception {
		given(pokemonService.getPokemon("missingno")).willThrow(new PokemonNotFoundException("missingno"));

		mockMvc.perform(get("/api/v1/pokemon/missingno"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.title").value("Pokemon not found"))
				.andExpect(jsonPath("$.status").value(404));
	}

	@Test
	void getPokemonReturns502WhenPokeApiFails() throws Exception {
		given(pokemonService.getPokemon("pikachu"))
				.willThrow(new PokeApiException("boom", new RuntimeException()));

		mockMvc.perform(get("/api/v1/pokemon/pikachu"))
				.andExpect(status().isBadGateway())
				.andExpect(jsonPath("$.title").value("Upstream service error"));
	}

	@Test
	void getPokemonReturns503WhenCircuitIsOpen() throws Exception {
		given(pokemonService.getPokemon("pikachu")).willThrow(
				CallNotPermittedException.createCallNotPermittedException(CircuitBreaker.ofDefaults("pokeapi")));

		mockMvc.perform(get("/api/v1/pokemon/pikachu"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.title").value("Service temporarily unavailable"));
	}

	@Test
	void getPokemonReturns429WhenRateLimited() throws Exception {
		given(pokemonService.getPokemon("pikachu")).willThrow(
				RequestNotPermitted.createRequestNotPermitted(RateLimiter.ofDefaults("pokeapi")));

		mockMvc.perform(get("/api/v1/pokemon/pikachu"))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.title").value("Too many requests"));
	}

	@Test
	void getPokemonRejectsInvalidInput() throws Exception {
		mockMvc.perform(get("/api/v1/pokemon/pika_chu!"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value("must be a pokemon name or numeric id"));

		verifyNoInteractions(pokemonService);
	}

	@Test
	void listPokemonUsesDefaultPaging() throws Exception {
		given(pokemonCatalogService.getPage(0, 20))
				.willReturn(new PokemonSummaryPage(List.of(BULBASAUR), 0, 20, 1302, 66));

		mockMvc.perform(get("/api/v1/pokemon"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[0].id").value(1))
				.andExpect(jsonPath("$.content[0].spriteUrl").value("sprite.png"))
				.andExpect(jsonPath("$.content[0].category[0]").value("grass"))
				.andExpect(jsonPath("$.content[0].category[1]").value("poison"))
				.andExpect(jsonPath("$.content[0].massKg").value(6.9))
				.andExpect(jsonPath("$.content[0].skills[1].name").value("chlorophyll"))
				.andExpect(jsonPath("$.content[0].skills[1].hidden").value(true))
				.andExpect(jsonPath("$.page").value(0))
				.andExpect(jsonPath("$.size").value(20))
				.andExpect(jsonPath("$.totalElements").value(1302))
				.andExpect(jsonPath("$.totalPages").value(66));
	}

	@Test
	void listPokemonPassesCustomPaging() throws Exception {
		given(pokemonCatalogService.getPage(3, 50)).willReturn(new PokemonSummaryPage(List.of(), 3, 50, 100, 2));

		mockMvc.perform(get("/api/v1/pokemon").param("page", "3").param("size", "50"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content").isEmpty())
				.andExpect(jsonPath("$.page").value(3));
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', quoteCharacter = '"', value = {
			"page=-1        | 'page' must be 0 or greater",
			"size=0         | 'size' must be between 1 and 50",
			"size=51        | 'size' must be between 1 and 50",
			"size=abc       | 'size' must be a number",
			"page=1.5       | 'page' must be a number" })
	void listPokemonRejectsInvalidPaging(String query, String detail) throws Exception {
		mockMvc.perform(get("/api/v1/pokemon?" + query))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Invalid request"))
				.andExpect(jsonPath("$.detail").value(detail));

		verifyNoInteractions(pokemonCatalogService);
	}

	@Test
	void listPokemonMapsUpstreamFailures() throws Exception {
		given(pokemonCatalogService.getPage(0, 20)).willThrow(new PokeApiException("boom", new RuntimeException()));
		mockMvc.perform(get("/api/v1/pokemon")).andExpect(status().isBadGateway());

		given(pokemonCatalogService.getPage(1, 20)).willThrow(
				CallNotPermittedException.createCallNotPermittedException(CircuitBreaker.ofDefaults("pokeapi")));
		mockMvc.perform(get("/api/v1/pokemon").param("page", "1")).andExpect(status().isServiceUnavailable());

		given(pokemonCatalogService.getPage(2, 20)).willThrow(
				RequestNotPermitted.createRequestNotPermitted(RateLimiter.ofDefaults("pokeapi")));
		mockMvc.perform(get("/api/v1/pokemon").param("page", "2")).andExpect(status().isTooManyRequests());
	}

}
