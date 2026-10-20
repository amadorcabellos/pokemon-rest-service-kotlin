package com.ballastlane.pokemon.sync

import com.ballastlane.pokemon.client.PokeApiClient
import com.ballastlane.pokemon.client.dto.PokeApiPokemon
import com.ballastlane.pokemon.client.dto.PokeApiPokemonList
import com.ballastlane.pokemon.client.dto.PokeApiPokemonList.Entry
import com.ballastlane.pokemon.config.SyncProperties
import com.ballastlane.pokemon.domain.SyncRunStatus
import com.ballastlane.pokemon.exception.PokeApiException
import com.ballastlane.pokemon.exception.PokemonNotFoundException
import com.ballastlane.pokemon.web.dto.SyncFailure
import com.ballastlane.pokemon.web.dto.SyncedPokemon
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Duration
import java.time.Instant
import org.junit.jupiter.api.Test

class FullSyncRunnerTest {

    private val pokeApiClient = mockk<PokeApiClient>()

    private val writer = mockk<PokemonSyncWriter>()

    private val store = mockk<SyncRunStore>(relaxUnitFun = true)

    private val runner = FullSyncRunner(
        pokeApiClient, writer, store,
        SyncProperties(2, Duration.ZERO, Duration.ZERO, 2, SyncProperties.Schedule(false, "0 0 3 * * *")),
    )

    @Test
    fun walksEveryPageAndCountsCreatedAndUpdated() {
        every { pokeApiClient.fetchPokemonPage(0, 2) } returns page(3, entry(1, "bulbasaur"), entry(2, "ivysaur"))
        every { pokeApiClient.fetchPokemonPage(2, 2) } returns page(3, entry(3, "venusaur"))
        stubUpsert(1, SyncOutcome.CREATED)
        stubUpsert(2, SyncOutcome.UPDATED)
        stubUpsert(3, SyncOutcome.CREATED)

        runner.run(RUN_ID)

        verify(exactly = 1) { store.recordProgress(RUN_ID, SyncProgress(3, 1, 1, 0), listOf()) }
        verify(exactly = 1) { store.recordProgress(RUN_ID, SyncProgress(3, 2, 1, 0), listOf()) }
        verify(exactly = 1) { store.finish(RUN_ID, SyncRunStatus.COMPLETED, SyncProgress(3, 2, 1, 0), listOf()) }
    }

    @Test
    fun recordsFailedPokemonAndCarriesOn() {
        every { pokeApiClient.fetchPokemonPage(0, 2) } returns page(2, entry(1, "bulbasaur"), entry(2, "ivysaur"))
        every { pokeApiClient.fetchPokemon("1") } throws PokemonNotFoundException("1")
        stubUpsert(2, SyncOutcome.CREATED)

        runner.run(RUN_ID)

        verify(exactly = 1) {
            store.recordProgress(
                RUN_ID, SyncProgress(2, 1, 0, 1), listOf(SyncFailure("bulbasaur", "Pokemon '1' was not found")),
            )
        }
        verify(exactly = 1) {
            store.finish(RUN_ID, SyncRunStatus.COMPLETED_WITH_ERRORS, SyncProgress(2, 1, 0, 1), listOf())
        }
    }

    @Test
    fun pausesAndRetriesWhenTheCircuitIsOpen() {
        every { pokeApiClient.fetchPokemonPage(0, 2) } returns page(1, entry(1, "bulbasaur"))
        val bulbasaur = pokemon(1)
        every { pokeApiClient.fetchPokemon("1") } throws circuitOpen() andThen bulbasaur
        every { writer.upsert(bulbasaur) } returns synced(1, SyncOutcome.CREATED)

        runner.run(RUN_ID)

        verify(exactly = 2) { pokeApiClient.fetchPokemon("1") }
        verify(exactly = 1) { store.finish(RUN_ID, SyncRunStatus.COMPLETED, SyncProgress(1, 1, 0, 0), listOf()) }
    }

    @Test
    fun failsTheRunWhenCallsKeepBeingRejected() {
        every { pokeApiClient.fetchPokemonPage(0, 2) } returns page(2, entry(1, "bulbasaur"), entry(2, "ivysaur"))
        every { pokeApiClient.fetchPokemon("1") } throws circuitOpen()

        runner.run(RUN_ID)

        // One call plus two pauses (max-consecutive-pauses = 2), then the run stops before ivysaur.
        verify(exactly = 3) { pokeApiClient.fetchPokemon("1") }
        verify(exactly = 0) { pokeApiClient.fetchPokemon("2") }
        verify(exactly = 1) { store.finish(RUN_ID, SyncRunStatus.FAILED, SyncProgress(2, 0, 0, 0), listOf()) }
    }

    @Test
    fun failsTheRunWhenTheCatalogueCannotBeRead() {
        every { pokeApiClient.fetchPokemonPage(0, 2) } throws PokeApiException("down", null)

        runner.run(RUN_ID)

        verify(exactly = 1) { store.finish(RUN_ID, SyncRunStatus.FAILED, SyncProgress(0, 0, 0, 0), listOf()) }
        verify(exactly = 0) { store.recordProgress(RUN_ID, any(), any()) }
    }

    @Test
    fun neverLeavesTheRunRunningOnUnexpectedErrors() {
        every { pokeApiClient.fetchPokemonPage(0, 2) } returns page(1, entry(1, "bulbasaur"))
        stubUpsert(1, SyncOutcome.CREATED)
        every { store.recordProgress(RUN_ID, any(), any()) } throws IllegalStateException("database down")

        runner.run(RUN_ID)

        verify(exactly = 1) { store.finish(RUN_ID, SyncRunStatus.FAILED, SyncProgress(1, 1, 0, 0), listOf()) }
    }

    @Test
    fun stopsOnAnEmptyPage() {
        every { pokeApiClient.fetchPokemonPage(any(), 2) } returns page(0)

        runner.run(RUN_ID)

        verify(exactly = 0) { pokeApiClient.fetchPokemon(any()) }
        verify(exactly = 1) { store.finish(RUN_ID, SyncRunStatus.COMPLETED, SyncProgress(0, 0, 0, 0), listOf()) }
    }

    private fun stubUpsert(id: Int, outcome: SyncOutcome) {
        val pokemon = pokemon(id)
        every { pokeApiClient.fetchPokemon(id.toString()) } returns pokemon
        every { writer.upsert(pokemon) } returns synced(id, outcome)
    }

    private companion object {

        const val RUN_ID = 1L

        fun pokemon(id: Int) = PokeApiPokemon(id, "pokemon-$id", 1, 1, 1, null, null, null, null, null)

        fun synced(id: Int, outcome: SyncOutcome) = SyncedPokemon(id, "pokemon-$id", outcome, Instant.now())

        fun page(count: Int, vararg entries: Entry) = PokeApiPokemonList(count, entries.toList())

        fun entry(id: Int, name: String) = Entry(name, "https://pokeapi.co/api/v2/pokemon/$id/")

        fun circuitOpen(): CallNotPermittedException =
            CallNotPermittedException.createCallNotPermittedException(CircuitBreaker.ofDefaults("pokeapi"))
    }
}
