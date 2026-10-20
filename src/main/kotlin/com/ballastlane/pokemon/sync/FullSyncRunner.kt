package com.ballastlane.pokemon.sync

import com.ballastlane.pokemon.client.PokeApiClient
import com.ballastlane.pokemon.client.dto.PokeApiPokemonList
import com.ballastlane.pokemon.config.SyncProperties
import com.ballastlane.pokemon.domain.SyncRunStatus
import com.ballastlane.pokemon.web.dto.SyncFailure
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.ratelimiter.RequestNotPermitted
import java.time.Duration
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Runs one full synchronization: walks the PokeAPI catalogue page by page and upserts every pokemon.
 *
 * Pokemon are synchronized one at a time, with `app.sync.delay-between-pokemon` between them, so the sync shares the
 * PokeAPI rate limit with normal API traffic. A pokemon that fails is recorded and skipped. When the circuit breaker
 * or rate limiter rejects a call, the runner waits and retries the same call, and gives up on the whole run after
 * `app.sync.max-consecutive-pauses` rejections in a row.
 */
@Component
class FullSyncRunner(
    private val pokeApiClient: PokeApiClient,
    private val writer: PokemonSyncWriter,
    private val store: SyncRunStore,
    private val properties: SyncProperties,
) {

    /**
     * Synchronizes the whole catalogue into the given (already started) run, and always finishes the run.
     */
    fun run(runId: Long) {
        val counters = Counters()
        val pendingFailures = mutableListOf<SyncFailure>()
        log.info("Synchronization run {} started", runId)
        try {
            var offset = 0
            while (true) {
                val pageOffset = offset
                val page = callPausingWhenRejected { pokeApiClient.fetchPokemonPage(pageOffset, properties.batchSize) }
                counters.catalogueCount = page.count
                for (entry in page.results) {
                    syncEntry(entry, counters, pendingFailures)
                }
                offset += page.results.size

                store.recordProgress(runId, counters.snapshot(), pendingFailures.toList())
                pendingFailures.clear()
                log.info("Synchronization run {}: {} of {} pokemon processed", runId, offset, page.count)

                // The count is re-read on every page, so pokemon added during the run are still picked up.
                if (page.results.isEmpty() || offset >= page.count) {
                    break
                }
            }
            val status = if (counters.failed > 0) SyncRunStatus.COMPLETED_WITH_ERRORS else SyncRunStatus.COMPLETED
            store.finish(runId, status, counters.snapshot(), pendingFailures)
            log.info(
                "Synchronization run {} {}: {} created, {} updated, {} failed", runId, status, counters.created,
                counters.updated, counters.failed,
            )
        } catch (ex: RuntimeException) {
            log.error(
                "Synchronization run {} failed: {} created, {} updated, {} failed before stopping", runId,
                counters.created, counters.updated, counters.failed, ex,
            )
            store.finish(runId, SyncRunStatus.FAILED, counters.snapshot(), pendingFailures)
        }
    }

    private fun syncEntry(entry: PokeApiPokemonList.Entry, counters: Counters, failures: MutableList<SyncFailure>) {
        try {
            val pokemon = callPausingWhenRejected { pokeApiClient.fetchPokemon(entry.id.toString()) }
            when (writer.upsert(pokemon).outcome) {
                SyncOutcome.CREATED -> counters.created++
                SyncOutcome.UPDATED -> counters.updated++
            }
        } catch (ex: SyncAbortedException) {
            throw ex
        } catch (ex: RuntimeException) {
            // Not found, failed after retries, or rejected by the database: keep the old row and carry on.
            counters.failed++
            val reason = ex.message ?: ex.javaClass.simpleName
            failures.add(SyncFailure(entry.name, reason))
            log.warn("Could not synchronize pokemon '{}': {}", entry.name, reason)
        }
        pause(properties.delayBetweenPokemon)
    }

    private fun <T> callPausingWhenRejected(call: () -> T): T {
        var pauses = 0
        while (true) {
            try {
                return call()
            } catch (ex: RuntimeException) {
                if (ex !is CallNotPermittedException && ex !is RequestNotPermitted) {
                    throw ex
                }
                if (++pauses > properties.maxConsecutivePauses) {
                    throw SyncAbortedException("PokeAPI calls were rejected $pauses times in a row", ex)
                }
                log.warn(
                    "PokeAPI call rejected ({}); pausing synchronization for {}", ex.message,
                    properties.pauseWhenRejected,
                )
                pause(properties.pauseWhenRejected)
            }
        }
    }

    private class Counters {

        var catalogueCount = 0

        var created = 0

        var updated = 0

        var failed = 0

        fun snapshot() = SyncProgress(catalogueCount, created, updated, failed)
    }

    private companion object {

        private val log = LoggerFactory.getLogger(FullSyncRunner::class.java)

        fun pause(duration: Duration) {
            if (duration.isZero || duration.isNegative) {
                return
            }
            try {
                Thread.sleep(duration)
            } catch (ex: InterruptedException) {
                Thread.currentThread().interrupt()
                throw SyncAbortedException("Synchronization was interrupted", ex)
            }
        }
    }
}
