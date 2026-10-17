package com.ballastlane.poke.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ballastlane.poke.client.PokeApiClient;
import com.ballastlane.poke.client.dto.PokeApiPokemon;
import com.ballastlane.poke.client.dto.PokeApiPokemon.NamedResource;
import com.ballastlane.poke.client.dto.PokeApiSpecies;
import com.ballastlane.poke.domain.Pokemon;
import com.ballastlane.poke.exception.PokeApiException;
import com.ballastlane.poke.exception.PokemonNotFoundException;
import com.ballastlane.poke.mapper.PokemonDetailsMapper;
import com.ballastlane.poke.mapper.PokemonMapper;
import com.ballastlane.poke.repository.PokemonRepository;
import com.ballastlane.poke.web.dto.EvolutionStage;
import com.ballastlane.poke.web.dto.PokemonDetails;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class PokemonDetailsServiceTest {

	private static final String API = "https://pokeapi.co/api/v2";

	@Mock
	private PokeApiClient pokeApiClient;

	@Mock
	private EvolutionChainLoader evolutionChainLoader;

	@Mock
	private PokemonRepository pokemonRepository;

	@Spy
	private PokemonMapper pokemonMapper = new PokemonMapper();

	@Spy
	private PokemonDetailsMapper detailsMapper = new PokemonDetailsMapper();

	@InjectMocks
	private PokemonDetailsService service;

	private static final EvolutionStage DEOXYS_CHAIN = new EvolutionStage(386, "deoxys", null, false, List.of());

	@Test
	void buildsViewFromPokemonSpeciesAndChainUsingTheLinkedSpecies() {
		given(pokeApiClient.fetchPokemon("deoxys-attack")).willReturn(pokemon(10001, "deoxys-attack", 386));
		given(pokemonRepository.findByPokeApiId(10001)).willReturn(Optional.empty());
		given(pokeApiClient.fetchSpecies(386)).willReturn(species(386, "deoxys", 186));
		given(evolutionChainLoader.load(186)).willReturn(DEOXYS_CHAIN);

		PokemonDetails details = service.getPokemon(" Deoxys-Attack ");

		assertThat(details.id()).isEqualTo(10001);
		assertThat(details.name()).isEqualTo("deoxys-attack");
		assertThat(details.evolution().name()).isEqualTo("deoxys");
		assertThat(details.evolution().current()).isTrue();
	}

	@Test
	void savesNewPokemon() {
		stubHappyPath();
		given(pokemonRepository.findByPokeApiId(25)).willReturn(Optional.empty());

		service.getPokemon("pikachu");

		verify(pokemonRepository).save(any(Pokemon.class));
	}

	@Test
	void skipsSavingAlreadyStoredPokemon() {
		stubHappyPath();
		given(pokemonRepository.findByPokeApiId(25))
				.willReturn(Optional.of(new Pokemon(25, "pikachu", 4, 60, 112, null, List.of())));

		service.getPokemon("pikachu");

		verify(pokemonRepository, never()).save(any());
	}

	@Test
	void ignoresConcurrentDuplicateSave() {
		stubHappyPath();
		given(pokemonRepository.findByPokeApiId(25)).willReturn(Optional.empty());
		given(pokemonRepository.save(any(Pokemon.class))).willThrow(new DataIntegrityViolationException("duplicate"));

		assertThat(service.getPokemon("pikachu").name()).isEqualTo("pikachu");
	}

	@Test
	void usesSingleStageWhenSpeciesHasNoChain() {
		given(pokeApiClient.fetchPokemon("pikachu")).willReturn(pokemon(25, "pikachu", 25));
		given(pokemonRepository.findByPokeApiId(25)).willReturn(Optional.empty());
		given(pokeApiClient.fetchSpecies(25)).willReturn(new PokeApiSpecies(25, "pikachu", List.of(), null));

		PokemonDetails details = service.getPokemon("pikachu");

		assertThat(details.evolution().name()).isEqualTo("pikachu");
		assertThat(details.evolution().evolvesTo()).isEmpty();
		verify(evolutionChainLoader, never()).load(anyInt());
	}

	@Test
	void propagatesNotFound() {
		given(pokeApiClient.fetchPokemon("missingno")).willThrow(new PokemonNotFoundException("missingno"));

		assertThatThrownBy(() -> service.getPokemon("missingno")).isInstanceOf(PokemonNotFoundException.class);
		verify(pokemonRepository, never()).save(any());
	}

	@Test
	void failsWholeRequestWhenSpeciesFails() {
		given(pokeApiClient.fetchPokemon("pikachu")).willReturn(pokemon(25, "pikachu", 25));
		given(pokemonRepository.findByPokeApiId(25)).willReturn(Optional.empty());
		given(pokeApiClient.fetchSpecies(25)).willThrow(new PokeApiException("boom", new RuntimeException()));

		assertThatThrownBy(() -> service.getPokemon("pikachu")).isInstanceOf(PokeApiException.class);
		verify(evolutionChainLoader, never()).load(anyInt());
	}

	@Test
	void failsWholeRequestWhenChainFails() {
		given(pokeApiClient.fetchPokemon("pikachu")).willReturn(pokemon(25, "pikachu", 25));
		given(pokemonRepository.findByPokeApiId(25)).willReturn(Optional.empty());
		given(pokeApiClient.fetchSpecies(25)).willReturn(species(25, "pikachu", 10));
		given(evolutionChainLoader.load(10)).willThrow(new PokeApiException("boom", new RuntimeException()));

		assertThatThrownBy(() -> service.getPokemon("pikachu")).isInstanceOf(PokeApiException.class);
	}

	private void stubHappyPath() {
		given(pokeApiClient.fetchPokemon("pikachu")).willReturn(pokemon(25, "pikachu", 25));
		given(pokeApiClient.fetchSpecies(25)).willReturn(species(25, "pikachu", 10));
		given(evolutionChainLoader.load(10)).willReturn(new EvolutionStage(25, "pikachu", null, false, List.of()));
	}

	private static PokeApiPokemon pokemon(int id, String name, int speciesId) {
		return new PokeApiPokemon(id, name, 4, 60, 112, null, List.of(), List.of(), List.of(),
				new NamedResource(name, API + "/pokemon-species/" + speciesId + "/"));
	}

	private static PokeApiSpecies species(int id, String name, int chainId) {
		return new PokeApiSpecies(id, name, List.of(),
				new PokeApiSpecies.Resource(API + "/evolution-chain/" + chainId + "/"));
	}

}
