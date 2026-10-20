package com.ballastlane.pokemon.sync

import com.ballastlane.pokemon.client.PokeApiClient
import com.ballastlane.pokemon.client.dto.PokeApiPokemon
import com.ballastlane.pokemon.domain.SyncRunStatus
import com.ballastlane.pokemon.domain.SyncTrigger
import com.ballastlane.pokemon.exception.PokemonNotFoundException
import com.ballastlane.pokemon.exception.SyncAlreadyRunningException
import com.ballastlane.pokemon.exception.SyncRunNotFoundException
import com.ballastlane.pokemon.web.dto.SyncRunReport
import com.ballastlane.pokemon.web.dto.SyncedPokemon
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import java.util.concurrent.Executor
import org.junit.jupiter.api.Test

class PokemonSyncServiceTest {

    private val pokeApiClient = mockk<PokeApiClient>()

    private val writer = mockk<PokemonSyncWriter>()

    private val store = mockk<SyncRunStore>()

    private val runner = mockk<FullSyncRunner>(relaxUnitFun = true)

    // Runs submitted tasks on the calling thread.
    private val service = PokemonSyncService(pokeApiClient, writer, store, runner, Executor { it.run() })

    @Test
    fun syncOneNormalisesInputAndUpserts() {
        val pikachu = PokeApiPokemon(25, "pikachu", 4, 60, 112, null, null, null, null, null)
        val synced = SyncedPokemon(25, "pikachu", SyncOutcome.CREATED, Instant.now())
        every { pokeApiClient.fetchPokemon("pikachu") } returns pikachu
        every { writer.upsert(pikachu) } returns synced

        service.syncOne("  PikaChu ") shouldBe synced
    }

    @Test
    fun syncOnePropagatesNotFound() {
        every { pokeApiClient.fetchPokemon("missingno") } throws PokemonNotFoundException("missingno")

        shouldThrow<PokemonNotFoundException> { service.syncOne("missingno") }
        verify { writer wasNot Called }
    }

    @Test
    fun startFullSyncRecordsTheRunAndRunsItInTheBackground() {
        every { store.start(SyncTrigger.API) } returns RUNNING

        service.startFullSync(SyncTrigger.API) shouldBe RUNNING
        verify(exactly = 1) { runner.run(7L) }
    }

    @Test
    fun startFullSyncDoesNotRunWhenAnotherRunIsRunning() {
        every { store.start(SyncTrigger.API) } throws SyncAlreadyRunningException(3L)

        shouldThrow<SyncAlreadyRunningException> { service.startFullSync(SyncTrigger.API) }
        verify(exactly = 0) { runner.run(any()) }
    }

    @Test
    fun getRunAndLatestRunThrowWhenMissing() {
        every { store.find(42L) } returns null
        every { store.findLatest() } returns null

        shouldThrow<SyncRunNotFoundException> { service.getRun(42L) }
        shouldThrow<SyncRunNotFoundException> { service.getLatestRun() }
    }

    private companion object {
        val RUNNING = SyncRunReport(7L, SyncTrigger.API, SyncRunStatus.RUNNING, Instant.now(), null, 0, 0, 0, 0, listOf())
    }
}
