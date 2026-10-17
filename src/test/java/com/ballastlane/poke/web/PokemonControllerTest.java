package com.ballastlane.poke.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ballastlane.poke.exception.PokeApiException;
import com.ballastlane.poke.exception.PokemonNotFoundException;
import com.ballastlane.poke.service.PokemonService;
import com.ballastlane.poke.web.dto.PokemonResponse;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PokemonController.class)
class PokemonControllerTest {

	private static final PokemonResponse PIKACHU = new PokemonResponse(25, "pikachu", 4, 60, 112, "sprite.png",
			List.of("electric"));

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private PokemonService pokemonService;

	@Test
	void getPokemonReturnsPokemon() throws Exception {
		given(pokemonService.getPokemon("pikachu")).willReturn(PIKACHU);

		mockMvc.perform(get("/api/v1/pokemon/pikachu"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(25))
				.andExpect(jsonPath("$.name").value("pikachu"))
				.andExpect(jsonPath("$.types[0]").value("electric"));
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
	void listStoredReturnsPage() throws Exception {
		given(pokemonService.listStored(any(Pageable.class)))
				.willReturn(new PageImpl<>(List.of(PIKACHU), PageRequest.of(0, 20), 1));

		mockMvc.perform(get("/api/v1/pokemon"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[0].name").value("pikachu"))
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.page").value(0));
	}

}
