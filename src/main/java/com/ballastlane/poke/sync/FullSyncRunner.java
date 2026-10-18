package com.ballastlane.poke.sync;

import com.ballastlane.poke.client.PokeApiClient;
import com.ballastlane.poke.client.dto.PokeApiPokemon;
import com.ballastlane.poke.client.dto.PokeApiPokemonList;
import com.ballastlane.poke.config.SyncProperties;
import com.ballastlane.poke.domain.SyncRunStatus;
import com.ballastlane.poke.web.dto.SyncFailure;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs one full synchronization: walks the PokeAPI catalogue page by page and upserts every pokemon.
 * <p>
 * Pokemon are synchronized one at a time, with {@code app.sync.delay-between-pokemon} between them, so the sync
 * shares the PokeAPI rate limit with normal API traffic. A pokemon that fails is recorded and skipped. When the
 * circuit breaker or rate limiter rejects a call, the runner waits and retries the same call, and gives up on
 * the whole run after {@code app.sync.max-consecutive-pauses} rejections in a row.
 */
@Component
public class FullSyncRunner {

	private static final Logger log = LoggerFactory.getLogger(FullSyncRunner.class);

	private final PokeApiClient pokeApiClient;

	private final PokemonSyncWriter writer;

	private final SyncRunStore store;

	private final SyncProperties properties;

	public FullSyncRunner(PokeApiClient pokeApiClient, PokemonSyncWriter writer, SyncRunStore store,
			SyncProperties properties) {
		this.pokeApiClient = pokeApiClient;
		this.writer = writer;
		this.store = store;
		this.properties = properties;
	}

	/**
	 * Synchronizes the whole catalogue into the given (already started) run, and always finishes the run.
	 */
	public void run(long runId) {
		Counters counters = new Counters();
		List<SyncFailure> pendingFailures = new ArrayList<>();
		log.info("Synchronization run {} started", runId);
		try {
			int offset = 0;
			while (true) {
				int pageOffset = offset;
				PokeApiPokemonList page = callPausingWhenRejected(
						() -> pokeApiClient.fetchPokemonPage(pageOffset, properties.batchSize()));
				counters.catalogueCount = page.count();
				for (PokeApiPokemonList.Entry entry : page.results()) {
					syncEntry(entry, counters, pendingFailures);
				}
				offset += page.results().size();

				store.recordProgress(runId, counters.snapshot(), List.copyOf(pendingFailures));
				pendingFailures.clear();
				log.info("Synchronization run {}: {} of {} pokemon processed", runId, offset, page.count());

				// The count is re-read on every page, so pokemon added during the run are still picked up.
				if (page.results().isEmpty() || offset >= page.count()) {
					break;
				}
			}
			SyncRunStatus status = counters.failed > 0 ? SyncRunStatus.COMPLETED_WITH_ERRORS : SyncRunStatus.COMPLETED;
			store.finish(runId, status, counters.snapshot(), pendingFailures);
			log.info("Synchronization run {} {}: {} created, {} updated, {} failed", runId, status, counters.created,
					counters.updated, counters.failed);
		}
		catch (RuntimeException ex) {
			log.error("Synchronization run {} failed: {} created, {} updated, {} failed before stopping", runId,
					counters.created, counters.updated, counters.failed, ex);
			store.finish(runId, SyncRunStatus.FAILED, counters.snapshot(), pendingFailures);
		}
	}

	private void syncEntry(PokeApiPokemonList.Entry entry, Counters counters, List<SyncFailure> failures) {
		try {
			PokeApiPokemon pokemon = callPausingWhenRejected(
					() -> pokeApiClient.fetchPokemon(String.valueOf(entry.id())));
			switch (writer.upsert(pokemon).outcome()) {
				case CREATED -> counters.created++;
				case UPDATED -> counters.updated++;
			}
		}
		catch (SyncAbortedException ex) {
			throw ex;
		}
		catch (RuntimeException ex) {
			// Not found, failed after retries, or rejected by the database: keep the old row and carry on.
			counters.failed++;
			String reason = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
			failures.add(new SyncFailure(entry.name(), reason));
			log.warn("Could not synchronize pokemon '{}': {}", entry.name(), reason);
		}
		pause(properties.delayBetweenPokemon());
	}

	private <T> T callPausingWhenRejected(Supplier<T> call) {
		int pauses = 0;
		while (true) {
			try {
				return call.get();
			}
			catch (CallNotPermittedException | RequestNotPermitted ex) {
				if (++pauses > properties.maxConsecutivePauses()) {
					throw new SyncAbortedException(
							"PokeAPI calls were rejected %d times in a row".formatted(pauses), ex);
				}
				log.warn("PokeAPI call rejected ({}); pausing synchronization for {}", ex.getMessage(),
						properties.pauseWhenRejected());
				pause(properties.pauseWhenRejected());
			}
		}
	}

	private static void pause(Duration duration) {
		if (duration.isZero() || duration.isNegative()) {
			return;
		}
		try {
			Thread.sleep(duration);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new SyncAbortedException("Synchronization was interrupted", ex);
		}
	}

	private static final class Counters {

		private int catalogueCount;

		private int created;

		private int updated;

		private int failed;

		SyncProgress snapshot() {
			return new SyncProgress(catalogueCount, created, updated, failed);
		}

	}

}
