package com.ballastlane.poke.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.ballastlane.poke.client.dto.PokeApiPokemon;
import com.ballastlane.poke.exception.PokeApiException;
import com.ballastlane.poke.exception.PokemonNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class PokeApiClientTest {

	private static final String BASE_URL = "https://pokeapi.test/api/v2";

	private MockRestServiceServer server;

	private PokeApiClient client;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
		server = MockRestServiceServer.bindTo(builder).build();
		client = new PokeApiClient(builder.build());
	}

	@Test
	void fetchPokemonMapsResponse() {
		server.expect(requestTo(BASE_URL + "/pokemon/pikachu"))
				.andExpect(method(HttpMethod.GET))
				.andRespond(withSuccess(new ClassPathResource("pokeapi/pikachu.json"), MediaType.APPLICATION_JSON));

		PokeApiPokemon pokemon = client.fetchPokemon("pikachu");

		assertThat(pokemon.id()).isEqualTo(25);
		assertThat(pokemon.name()).isEqualTo("pikachu");
		assertThat(pokemon.baseExperience()).isEqualTo(112);
		assertThat(pokemon.sprites().frontDefault()).endsWith("/25.png");
		assertThat(pokemon.types()).extracting(slot -> slot.type().name()).containsExactly("electric");
		server.verify();
	}

	@Test
	void fetchPokemonThrowsNotFoundOn404() {
		server.expect(requestTo(BASE_URL + "/pokemon/missingno")).andRespond(withResourceNotFound());

		assertThatThrownBy(() -> client.fetchPokemon("missingno"))
				.isInstanceOf(PokemonNotFoundException.class)
				.hasMessageContaining("missingno");
	}

	@Test
	void fetchPokemonWrapsServerErrors() {
		server.expect(requestTo(BASE_URL + "/pokemon/pikachu")).andRespond(withServerError());

		assertThatThrownBy(() -> client.fetchPokemon("pikachu")).isInstanceOf(PokeApiException.class);
	}

}
