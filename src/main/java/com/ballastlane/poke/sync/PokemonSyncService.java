package com.ballastlane.poke.sync;

import com.ballastlane.poke.client.PokeApiClient;
import com.ballastlane.poke.domain.SyncTrigger;
import com.ballastlane.poke.exception.SyncAlreadyRunningException;
import com.ballastlane.poke.exception.SyncRunNotFoundException;
import com.ballastlane.poke.web.dto.SyncRunReport;
import com.ballastlane.poke.web.dto.SyncedPokemon;
import java.util.Locale;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * Synchronizes PokeAPI pokemon into PostgreSQL: one pokemon on request, or the whole catalogue in the background.
 */
@Service
public class PokemonSyncService {

	private final PokeApiClient pokeApiClient;

	private final PokemonSyncWriter writer;

	private final SyncRunStore store;

	private final FullSyncRunner runner;

	private final Executor executor;

	public PokemonSyncService(PokeApiClient pokeApiClient, PokemonSyncWriter writer, SyncRunStore store,
			FullSyncRunner runner, @Qualifier("applicationTaskExecutor") Executor executor) {
		this.pokeApiClient = pokeApiClient;
		this.writer = writer;
		this.store = store;
		this.runner = runner;
		this.executor = executor;
	}

	/**
	 * Fetches one pokemon and creates or refreshes its local copy. Not transactional on purpose: the PokeAPI call
	 * (including retries) must not hold a database connection.
	 */
	public SyncedPokemon syncOne(String nameOrId) {
		return writer.upsert(pokeApiClient.fetchPokemon(nameOrId.trim().toLowerCase(Locale.ROOT)));
	}

	/**
	 * Starts a full synchronization in the background and returns the new run straight away.
	 *
	 * @throws SyncAlreadyRunningException if another full synchronization is still running
	 */
	public SyncRunReport startFullSync(SyncTrigger trigger) {
		SyncRunReport run = store.start(trigger);
		executor.execute(() -> runner.run(run.id()));
		return run;
	}

	public SyncRunReport getRun(long runId) {
		return store.find(runId)
				.orElseThrow(() -> new SyncRunNotFoundException("Synchronization run %d was not found".formatted(runId)));
	}

	public SyncRunReport getLatestRun() {
		return store.findLatest()
				.orElseThrow(() -> new SyncRunNotFoundException("No synchronization run has been started yet"));
	}

}
