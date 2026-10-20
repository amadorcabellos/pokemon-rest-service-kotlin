package com.ballastlane.pokemon.sync

import com.ballastlane.pokemon.client.PokeApiClient
import com.ballastlane.pokemon.domain.SyncTrigger
import com.ballastlane.pokemon.exception.SyncAlreadyRunningException
import com.ballastlane.pokemon.exception.SyncRunNotFoundException
import com.ballastlane.pokemon.text.javaTrim
import com.ballastlane.pokemon.web.dto.SyncRunReport
import com.ballastlane.pokemon.web.dto.SyncedPokemon
import java.util.Locale
import java.util.concurrent.Executor
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service

/**
 * Synchronizes PokeAPI pokemon into PostgreSQL: one pokemon on request, or the whole catalogue in the background.
 */
@Service
class PokemonSyncService(
    private val pokeApiClient: PokeApiClient,
    private val writer: PokemonSyncWriter,
    private val store: SyncRunStore,
    private val runner: FullSyncRunner,
    @Qualifier("applicationTaskExecutor") private val executor: Executor,
) {

    /**
     * Fetches one pokemon and creates or refreshes its local copy. Not transactional on purpose: the PokeAPI call
     * (including retries) must not hold a database connection.
     */
    fun syncOne(nameOrId: String): SyncedPokemon =
        writer.upsert(pokeApiClient.fetchPokemon(nameOrId.javaTrim().lowercase(Locale.ROOT)))

    /**
     * Starts a full synchronization in the background and returns the new run straight away.
     *
     * @throws SyncAlreadyRunningException if another full synchronization is still running
     */
    fun startFullSync(trigger: SyncTrigger): SyncRunReport {
        val run = store.start(trigger)
        val runId = checkNotNull(run.id)
        executor.execute { runner.run(runId) }
        return run
    }

    fun getRun(runId: Long): SyncRunReport =
        store.find(runId) ?: throw SyncRunNotFoundException("Synchronization run $runId was not found")

    fun getLatestRun(): SyncRunReport =
        store.findLatest() ?: throw SyncRunNotFoundException("No synchronization run has been started yet")
}
