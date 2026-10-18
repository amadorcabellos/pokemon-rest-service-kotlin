package com.ballastlane.poke.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.ballastlane.poke.client.dto.PokeApiPokemon;
import com.ballastlane.poke.client.dto.PokeApiPokemon.NamedResource;
import com.ballastlane.poke.client.dto.PokeApiPokemon.StatSlot;
import com.ballastlane.poke.domain.Pokemon;
import com.ballastlane.poke.domain.PokemonStat;
import com.ballastlane.poke.mapper.PokemonMapper;
import com.ballastlane.poke.repository.PokemonRepository;
import com.ballastlane.poke.web.dto.SyncedPokemon;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class PokemonSyncWriterTest {

	private static final PokeApiPokemon PIKACHU = new PokeApiPokemon(25, "pikachu", 4, 60, 112, null, List.of(),
			List.of(), List.of(new StatSlot(35, new NamedResource("hp", null))),
			new NamedResource("pikachu", "https://pokeapi.co/api/v2/pokemon-species/25/"));

	@Mock
	private PokemonRepository repository;

	private PokemonSyncWriter writer;

	@BeforeEach
	void setUp() {
		writer = new PokemonSyncWriter(repository, new PokemonMapper(),
				new TransactionTemplate(mock(PlatformTransactionManager.class)));
	}

	@Test
	void createsPokemonThatIsNotStoredYet() {
		given(repository.findByPokeApiId(25)).willReturn(Optional.empty());

		SyncedPokemon result = writer.upsert(PIKACHU);

		assertThat(result.id()).isEqualTo(25);
		assertThat(result.name()).isEqualTo("pikachu");
		assertThat(result.outcome()).isEqualTo(SyncOutcome.CREATED);
		assertThat(result.lastSyncedAt()).isNotNull();
		ArgumentCaptor<Pokemon> saved = ArgumentCaptor.forClass(Pokemon.class);
		verify(repository).saveAndFlush(saved.capture());
		assertThat(saved.getValue().getStats()).containsExactly(new PokemonStat("hp", 35));
		assertThat(saved.getValue().getLastSyncedAt()).isEqualTo(result.lastSyncedAt());
	}

	@Test
	void updatesStoredPokemonMatchedByPokeApiIdIncludingRenames() {
		Pokemon stored = new Pokemon(25, "pikachu-old", 4, 60, 112, null, List.of());
		given(repository.findByPokeApiId(25)).willReturn(Optional.of(stored));

		SyncedPokemon result = writer.upsert(PIKACHU);

		assertThat(result.outcome()).isEqualTo(SyncOutcome.UPDATED);
		assertThat(result.name()).isEqualTo("pikachu");
		assertThat(stored.getName()).isEqualTo("pikachu");
		assertThat(stored.getStats()).containsExactly(new PokemonStat("hp", 35));
		assertThat(stored.getSpeciesId()).isEqualTo(25);
		assertThat(stored.getLastSyncedAt()).isEqualTo(result.lastSyncedAt());
		verify(repository).saveAndFlush(stored);
	}

	@Test
	void retriesAsUpdateWhenInsertedConcurrently() {
		Pokemon storedMeanwhile = new Pokemon(25, "pikachu", 4, 60, 112, null, List.of());
		given(repository.findByPokeApiId(25)).willReturn(Optional.empty(), Optional.of(storedMeanwhile));
		given(repository.saveAndFlush(any())).willThrow(new DataIntegrityViolationException("duplicate"))
				.willAnswer(invocation -> invocation.getArgument(0));

		SyncedPokemon result = writer.upsert(PIKACHU);

		assertThat(result.outcome()).isEqualTo(SyncOutcome.UPDATED);
		verify(repository, times(2)).saveAndFlush(any());
	}

}
