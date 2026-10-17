package com.ballastlane.poke.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ballastlane.poke.client.PokeApiClient;
import com.ballastlane.poke.client.dto.PokeApiPokemon;
import com.ballastlane.poke.domain.Pokemon;
import com.ballastlane.poke.exception.PokemonNotFoundException;
import com.ballastlane.poke.mapper.PokemonMapper;
import com.ballastlane.poke.repository.PokemonRepository;
import com.ballastlane.poke.web.dto.PokemonResponse;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PokemonServiceTest {

	@Mock
	private PokeApiClient pokeApiClient;

	@Mock
	private PokemonRepository pokemonRepository;

	@Spy
	private PokemonMapper pokemonMapper = new PokemonMapper();

	@InjectMocks
	private PokemonService pokemonService;

	private final Pokemon pikachu = new Pokemon(25, "pikachu", 4, 60, 112, "sprite.png", List.of("electric"));

	@Test
	void returnsStoredPokemonWithoutCallingPokeApi() {
		given(pokemonRepository.findByNameIgnoreCase("pikachu")).willReturn(Optional.of(pikachu));

		PokemonResponse response = pokemonService.getPokemon(" Pikachu ");

		assertThat(response.name()).isEqualTo("pikachu");
		verify(pokeApiClient, never()).fetchPokemon(anyString());
		verify(pokemonRepository, never()).save(any());
	}

	@Test
	void looksUpByPokeApiIdWhenInputIsNumeric() {
		given(pokemonRepository.findByPokeApiId(25)).willReturn(Optional.of(pikachu));

		PokemonResponse response = pokemonService.getPokemon("25");

		assertThat(response.id()).isEqualTo(25);
		verify(pokemonRepository, never()).findByNameIgnoreCase(anyString());
	}

	@Test
	void fetchesFromPokeApiAndStoresWhenMissingLocally() {
		PokeApiPokemon remote = new PokeApiPokemon(25, "pikachu", 4, 60, 112,
				new PokeApiPokemon.Sprites("sprite.png"),
				List.of(new PokeApiPokemon.TypeSlot(1, new PokeApiPokemon.NamedResource("electric", null))),
				List.of());
		given(pokemonRepository.findByNameIgnoreCase("pikachu")).willReturn(Optional.empty());
		given(pokeApiClient.fetchPokemon("pikachu")).willReturn(remote);
		given(pokemonRepository.save(any(Pokemon.class))).willAnswer(invocation -> invocation.getArgument(0));

		PokemonResponse response = pokemonService.getPokemon("pikachu");

		assertThat(response).isEqualTo(new PokemonResponse(25, "pikachu", 4, 60, 112, "sprite.png",
				List.of("electric")));
		verify(pokemonRepository).save(any(Pokemon.class));
	}

	@Test
	void propagatesNotFoundFromPokeApi() {
		given(pokemonRepository.findByNameIgnoreCase("missingno")).willReturn(Optional.empty());
		given(pokeApiClient.fetchPokemon("missingno")).willThrow(new PokemonNotFoundException("missingno"));

		assertThatThrownBy(() -> pokemonService.getPokemon("missingno"))
				.isInstanceOf(PokemonNotFoundException.class);
		verify(pokemonRepository, never()).save(any());
	}

}
