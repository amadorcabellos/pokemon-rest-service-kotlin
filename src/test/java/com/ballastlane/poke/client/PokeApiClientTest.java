package com.ballastlane.poke.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.ballastlane.poke.client.dto.PokeApiEvolutionChain;
import com.ballastlane.poke.client.dto.PokeApiPokemon;
import com.ballastlane.poke.client.dto.PokeApiPokemonList;
import com.ballastlane.poke.client.dto.PokeApiSpecies;
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
		assertThat(pokemon.sprites().other().officialArtwork().frontDefault()).endsWith("/official-artwork/25.png");
		assertThat(pokemon.stats()).extracting(slot -> slot.stat().name(), PokeApiPokemon.StatSlot::baseStat)
				.startsWith(tuple("hp", 35), tuple("attack", 55));
		assertThat(pokemon.species().url()).endsWith("/pokemon-species/25/");
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
	void fetchSpeciesParsesFlavorTextsAndChainLink() {
		server.expect(requestTo(BASE_URL + "/pokemon-species/25"))
				.andRespond(withSuccess(new ClassPathResource("pokeapi/pikachu-species.json"), MediaType.APPLICATION_JSON));

		PokeApiSpecies species = client.fetchSpecies(25);

		assertThat(species.id()).isEqualTo(25);
		assertThat(species.flavorTextEntries()).hasSize(3);
		assertThat(species.flavorTextEntries().get(0).language().name()).isEqualTo("en");
		assertThat(species.flavorTextEntries().get(0).version().name()).isEqualTo("red");
		assertThat(species.evolutionChain().url()).endsWith("/evolution-chain/10/");
		server.verify();
	}

	@Test
	void fetchEvolutionChainParsesTree() {
		server.expect(requestTo(BASE_URL + "/evolution-chain/10"))
				.andRespond(withSuccess(new ClassPathResource("pokeapi/evolution-chain-10.json"), MediaType.APPLICATION_JSON));

		PokeApiEvolutionChain chain = client.fetchEvolutionChain(10);

		assertThat(chain.chain().species().name()).isEqualTo("pichu");
		PokeApiEvolutionChain.ChainLink pikachu = chain.chain().evolvesTo().get(0);
		assertThat(pikachu.species().name()).isEqualTo("pikachu");
		assertThat(pikachu.evolutionDetails().get(0).minHappiness()).isEqualTo(220);
		assertThat(pikachu.evolvesTo().get(0).evolutionDetails().get(0).item().name()).isEqualTo("thunder-stone");
		server.verify();
	}

	@Test
	void fetchSpeciesAndChainWrapServerErrorsAndNotFound() {
		server.expect(requestTo(BASE_URL + "/pokemon-species/25")).andRespond(withServerError());
		server.expect(requestTo(BASE_URL + "/evolution-chain/10")).andRespond(withResourceNotFound());

		assertThatThrownBy(() -> client.fetchSpecies(25)).isInstanceOf(PokeApiException.class);
		assertThatThrownBy(() -> client.fetchEvolutionChain(10)).isInstanceOf(PokeApiException.class);
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
