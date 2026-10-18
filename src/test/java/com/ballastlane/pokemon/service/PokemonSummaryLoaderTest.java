package com.ballastlane.pokemon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.ballastlane.pokemon.client.PokeApiClient;
import com.ballastlane.pokemon.client.dto.PokeApiPokemon;
import com.ballastlane.pokemon.exception.PokeApiException;
import com.ballastlane.pokemon.mapper.PokemonMapper;
import com.ballastlane.pokemon.web.dto.PokemonSummary;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PokemonSummaryLoaderTest {

	@Mock
	private PokeApiClient pokeApiClient;

	@Spy
	private PokemonMapper pokemonMapper = new PokemonMapper();

	@InjectMocks
	private PokemonSummaryLoader loader;

	@Test
	void fetchesByIdAndMapsToSummary() {
		given(pokeApiClient.fetchPokemon("25")).willReturn(new PokeApiPokemon(25, "pikachu", 4, 60, 112,
				new PokeApiPokemon.Sprites("sprite.png", null),
				List.of(new PokeApiPokemon.TypeSlot(1, new PokeApiPokemon.NamedResource("electric", null))),
				List.of(new PokeApiPokemon.AbilitySlot(1, false, new PokeApiPokemon.NamedResource("static", null))),
				null, null));

		PokemonSummary summary = loader.load(25);

		assertThat(summary.name()).isEqualTo("pikachu");
		assertThat(summary.category()).containsExactly("electric");
		assertThat(summary.massKg()).isEqualByComparingTo("6.0");
	}

	@Test
	void propagatesClientErrors() {
		given(pokeApiClient.fetchPokemon("25")).willThrow(new PokeApiException("boom", new RuntimeException()));

		assertThatThrownBy(() -> loader.load(25)).isInstanceOf(PokeApiException.class);
	}

}
