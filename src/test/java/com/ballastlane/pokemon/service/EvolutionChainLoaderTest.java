package com.ballastlane.pokemon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.ballastlane.pokemon.client.PokeApiClient;
import com.ballastlane.pokemon.client.dto.PokeApiEvolutionChain;
import com.ballastlane.pokemon.client.dto.PokeApiEvolutionChain.ChainLink;
import com.ballastlane.pokemon.client.dto.PokeApiPokemon.NamedResource;
import com.ballastlane.pokemon.exception.PokeApiException;
import com.ballastlane.pokemon.mapper.PokemonDetailsMapper;
import com.ballastlane.pokemon.web.dto.EvolutionStage;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EvolutionChainLoaderTest {

	@Mock
	private PokeApiClient pokeApiClient;

	@Spy
	private PokemonDetailsMapper detailsMapper = new PokemonDetailsMapper();

	@InjectMocks
	private EvolutionChainLoader loader;

	@Test
	void fetchesAndMapsChainWithoutCurrentStage() {
		given(pokeApiClient.fetchEvolutionChain(53)).willReturn(new PokeApiEvolutionChain(53, new ChainLink(
				new NamedResource("tauros", "https://pokeapi.co/api/v2/pokemon-species/128/"), List.of(), List.of())));

		EvolutionStage stage = loader.load(53);

		assertThat(stage.id()).isEqualTo(128);
		assertThat(stage.name()).isEqualTo("tauros");
		assertThat(stage.current()).isFalse();
	}

	@Test
	void propagatesClientErrors() {
		given(pokeApiClient.fetchEvolutionChain(53)).willThrow(new PokeApiException("boom", new RuntimeException()));

		assertThatThrownBy(() -> loader.load(53)).isInstanceOf(PokeApiException.class);
	}

}
