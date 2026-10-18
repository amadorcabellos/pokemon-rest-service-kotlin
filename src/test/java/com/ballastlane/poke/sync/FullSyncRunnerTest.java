package com.ballastlane.poke.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.ballastlane.poke.client.PokeApiClient;
import com.ballastlane.poke.client.dto.PokeApiPokemon;
import com.ballastlane.poke.client.dto.PokeApiPokemonList;
import com.ballastlane.poke.client.dto.PokeApiPokemonList.Entry;
import com.ballastlane.poke.config.SyncProperties;
import com.ballastlane.poke.domain.SyncRunStatus;
import com.ballastlane.poke.exception.PokeApiException;
import com.ballastlane.poke.exception.PokemonNotFoundException;
import com.ballastlane.poke.web.dto.SyncFailure;
import com.ballastlane.poke.web.dto.SyncedPokemon;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FullSyncRunnerTest {

	private static final long RUN_ID = 1L;

	@Mock
	private PokeApiClient pokeApiClient;

	@Mock
	private PokemonSyncWriter writer;

	@Mock
	private SyncRunStore store;

	private FullSyncRunner runner;

	@BeforeEach
	void setUp() {
		SyncProperties properties = new SyncProperties(2, Duration.ZERO, Duration.ZERO, 2,
				new SyncProperties.Schedule(false, "0 0 3 * * *"));
		runner = new FullSyncRunner(pokeApiClient, writer, store, properties);
	}

	@Test
	void walksEveryPageAndCountsCreatedAndUpdated() {
		given(pokeApiClient.fetchPokemonPage(0, 2)).willReturn(page(3, entry(1, "bulbasaur"), entry(2, "ivysaur")));
		given(pokeApiClient.fetchPokemonPage(2, 2)).willReturn(page(3, entry(3, "venusaur")));
		stubUpsert(1, SyncOutcome.CREATED);
		stubUpsert(2, SyncOutcome.UPDATED);
		stubUpsert(3, SyncOutcome.CREATED);

		runner.run(RUN_ID);

		verify(store).recordProgress(RUN_ID, new SyncProgress(3, 1, 1, 0), List.of());
		verify(store).recordProgress(RUN_ID, new SyncProgress(3, 2, 1, 0), List.of());
		verify(store).finish(RUN_ID, SyncRunStatus.COMPLETED, new SyncProgress(3, 2, 1, 0), List.of());
	}

	@Test
	void recordsFailedPokemonAndCarriesOn() {
		given(pokeApiClient.fetchPokemonPage(0, 2)).willReturn(page(2, entry(1, "bulbasaur"), entry(2, "ivysaur")));
		given(pokeApiClient.fetchPokemon("1")).willThrow(new PokemonNotFoundException("1"));
		stubUpsert(2, SyncOutcome.CREATED);

		runner.run(RUN_ID);

		verify(store).recordProgress(RUN_ID, new SyncProgress(2, 1, 0, 1),
				List.of(new SyncFailure("bulbasaur", "Pokemon '1' was not found")));
		verify(store).finish(RUN_ID, SyncRunStatus.COMPLETED_WITH_ERRORS, new SyncProgress(2, 1, 0, 1), List.of());
	}

	@Test
	void pausesAndRetriesWhenTheCircuitIsOpen() {
		given(pokeApiClient.fetchPokemonPage(0, 2)).willReturn(page(1, entry(1, "bulbasaur")));
		PokeApiPokemon bulbasaur = pokemon(1);
		given(pokeApiClient.fetchPokemon("1")).willThrow(circuitOpen()).willReturn(bulbasaur);
		given(writer.upsert(bulbasaur)).willReturn(synced(1, SyncOutcome.CREATED));

		runner.run(RUN_ID);

		verify(pokeApiClient, times(2)).fetchPokemon("1");
		verify(store).finish(RUN_ID, SyncRunStatus.COMPLETED, new SyncProgress(1, 1, 0, 0), List.of());
	}

	@Test
	void failsTheRunWhenCallsKeepBeingRejected() {
		given(pokeApiClient.fetchPokemonPage(0, 2)).willReturn(page(2, entry(1, "bulbasaur"), entry(2, "ivysaur")));
		given(pokeApiClient.fetchPokemon("1")).willThrow(circuitOpen());

		runner.run(RUN_ID);

		// One call plus two pauses (max-consecutive-pauses = 2), then the run stops before ivysaur.
		verify(pokeApiClient, times(3)).fetchPokemon("1");
		verify(pokeApiClient, never()).fetchPokemon("2");
		verify(store).finish(RUN_ID, SyncRunStatus.FAILED, new SyncProgress(2, 0, 0, 0), List.of());
	}

	@Test
	void failsTheRunWhenTheCatalogueCannotBeRead() {
		given(pokeApiClient.fetchPokemonPage(0, 2)).willThrow(new PokeApiException("down", null));

		runner.run(RUN_ID);

		verify(store).finish(RUN_ID, SyncRunStatus.FAILED, new SyncProgress(0, 0, 0, 0), List.of());
		verify(store, never()).recordProgress(eq(RUN_ID), any(), anyList());
	}

	@Test
	void neverLeavesTheRunRunningOnUnexpectedErrors() {
		given(pokeApiClient.fetchPokemonPage(0, 2)).willReturn(page(1, entry(1, "bulbasaur")));
		stubUpsert(1, SyncOutcome.CREATED);
		doThrow(new IllegalStateException("database down"))
				.when(store).recordProgress(eq(RUN_ID), any(), anyList());

		runner.run(RUN_ID);

		verify(store).finish(RUN_ID, SyncRunStatus.FAILED, new SyncProgress(1, 1, 0, 0), List.of());
	}

	@Test
	void stopsOnAnEmptyPage() {
		given(pokeApiClient.fetchPokemonPage(anyInt(), eq(2))).willReturn(page(0));

		runner.run(RUN_ID);

		verify(pokeApiClient, never()).fetchPokemon(any());
		verify(store).finish(RUN_ID, SyncRunStatus.COMPLETED, new SyncProgress(0, 0, 0, 0), List.of());
	}

	private void stubUpsert(int id, SyncOutcome outcome) {
		PokeApiPokemon pokemon = pokemon(id);
		given(pokeApiClient.fetchPokemon(String.valueOf(id))).willReturn(pokemon);
		given(writer.upsert(pokemon)).willReturn(synced(id, outcome));
	}

	private static PokeApiPokemon pokemon(int id) {
		return new PokeApiPokemon(id, "pokemon-" + id, 1, 1, 1, null, null, null, null, null);
	}

	private static SyncedPokemon synced(int id, SyncOutcome outcome) {
		return new SyncedPokemon(id, "pokemon-" + id, outcome, Instant.now());
	}

	private static PokeApiPokemonList page(int count, Entry... entries) {
		return new PokeApiPokemonList(count, List.of(entries));
	}

	private static Entry entry(int id, String name) {
		return new Entry(name, "https://pokeapi.co/api/v2/pokemon/%d/".formatted(id));
	}

	private static CallNotPermittedException circuitOpen() {
		return CallNotPermittedException.createCallNotPermittedException(CircuitBreaker.ofDefaults("pokeapi"));
	}

}
