package com.ballastlane.poke.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.ballastlane.poke.client.PokeApiClient;
import com.ballastlane.poke.client.dto.PokeApiPokemon;
import com.ballastlane.poke.domain.SyncRunStatus;
import com.ballastlane.poke.domain.SyncTrigger;
import com.ballastlane.poke.exception.PokemonNotFoundException;
import com.ballastlane.poke.exception.SyncAlreadyRunningException;
import com.ballastlane.poke.exception.SyncRunNotFoundException;
import com.ballastlane.poke.web.dto.SyncRunReport;
import com.ballastlane.poke.web.dto.SyncedPokemon;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PokemonSyncServiceTest {

	private static final SyncRunReport RUNNING = new SyncRunReport(7L, SyncTrigger.API, SyncRunStatus.RUNNING,
			Instant.now(), null, 0, 0, 0, 0, List.of());

	@Mock
	private PokeApiClient pokeApiClient;

	@Mock
	private PokemonSyncWriter writer;

	@Mock
	private SyncRunStore store;

	@Mock
	private FullSyncRunner runner;

	private PokemonSyncService service;

	@BeforeEach
	void setUp() {
		// Runs submitted tasks on the calling thread.
		service = new PokemonSyncService(pokeApiClient, writer, store, runner, Runnable::run);
	}

	@Test
	void syncOneNormalisesInputAndUpserts() {
		PokeApiPokemon pikachu = new PokeApiPokemon(25, "pikachu", 4, 60, 112, null, null, null, null, null);
		SyncedPokemon synced = new SyncedPokemon(25, "pikachu", SyncOutcome.CREATED, Instant.now());
		given(pokeApiClient.fetchPokemon("pikachu")).willReturn(pikachu);
		given(writer.upsert(pikachu)).willReturn(synced);

		assertThat(service.syncOne("  PikaChu ")).isEqualTo(synced);
	}

	@Test
	void syncOnePropagatesNotFound() {
		given(pokeApiClient.fetchPokemon("missingno")).willThrow(new PokemonNotFoundException("missingno"));

		assertThatThrownBy(() -> service.syncOne("missingno")).isInstanceOf(PokemonNotFoundException.class);
		verifyNoInteractions(writer);
	}

	@Test
	void startFullSyncRecordsTheRunAndRunsItInTheBackground() {
		given(store.start(SyncTrigger.API)).willReturn(RUNNING);

		assertThat(service.startFullSync(SyncTrigger.API)).isEqualTo(RUNNING);
		verify(runner).run(7L);
	}

	@Test
	void startFullSyncDoesNotRunWhenAnotherRunIsRunning() {
		given(store.start(SyncTrigger.API)).willThrow(new SyncAlreadyRunningException(3L));

		assertThatThrownBy(() -> service.startFullSync(SyncTrigger.API))
				.isInstanceOf(SyncAlreadyRunningException.class);
		verify(runner, never()).run(anyLong());
	}

	@Test
	void getRunAndLatestRunThrowWhenMissing() {
		given(store.find(42L)).willReturn(Optional.empty());
		given(store.findLatest()).willReturn(Optional.empty());

		assertThatThrownBy(() -> service.getRun(42L)).isInstanceOf(SyncRunNotFoundException.class);
		assertThatThrownBy(() -> service.getLatestRun()).isInstanceOf(SyncRunNotFoundException.class);
	}

}
