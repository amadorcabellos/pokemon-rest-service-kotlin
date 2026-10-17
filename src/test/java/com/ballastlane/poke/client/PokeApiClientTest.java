package com.ballastlane.poke.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.ballastlane.poke.client.dto.PokeApiPokemon;
import com.ballastlane.poke.client.dto.PokeApiPokemonList;
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
		assertThat(pokemon.abilities()).extracting(slot -> slot.ability().name(), PokeApiPokemon.AbilitySlot::hidden)
				.containsExactly(tuple("static", false), tuple("lightning-rod", true));
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
	void fetchPokemonPageParsesCatalogueListing() {
		server.expect(requestTo(BASE_URL + "/pokemon?offset=0&limit=2"))
				.andExpect(method(HttpMethod.GET))
				.andRespond(withSuccess(new ClassPathResource("pokeapi/pokemon-list.json"), MediaType.APPLICATION_JSON));

		PokeApiPokemonList listing = client.fetchPokemonPage(0, 2);

		assertThat(listing.count()).isEqualTo(1302);
		assertThat(listing.results()).extracting(PokeApiPokemonList.Entry::name).containsExactly("bulbasaur", "ivysaur");
		assertThat(listing.results()).extracting(PokeApiPokemonList.Entry::id).containsExactly(1, 2);
		server.verify();
	}

	@Test
	void fetchPokemonPageWrapsServerErrors() {
		server.expect(requestTo(BASE_URL + "/pokemon?offset=0&limit=2")).andRespond(withServerError());

		assertThatThrownBy(() -> client.fetchPokemonPage(0, 2)).isInstanceOf(PokeApiException.class);
	}

	@Test
	void fetchPokemonWrapsServerErrors() {
		server.expect(requestTo(BASE_URL + "/pokemon/pikachu")).andRespond(withServerError());

		assertThatThrownBy(() -> client.fetchPokemon("pikachu")).isInstanceOf(PokeApiException.class);
	}

}
