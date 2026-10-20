package com.ballastlane.pokemon.sync

import com.ballastlane.pokemon.client.dto.PokeApiPokemon
import com.ballastlane.pokemon.client.dto.PokeApiPokemon.NamedResource
import com.ballastlane.pokemon.client.dto.PokeApiPokemon.StatSlot
import com.ballastlane.pokemon.domain.Pokemon
import com.ballastlane.pokemon.domain.PokemonStat
import com.ballastlane.pokemon.mapper.PokemonMapper
import com.ballastlane.pokemon.repository.PokemonRepository
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.util.Optional
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

class PokemonSyncWriterTest {

    private val repository = mockk<PokemonRepository>()

    private val writer = PokemonSyncWriter(
        repository, PokemonMapper(), TransactionTemplate(mockk<PlatformTransactionManager>(relaxed = true)),
    )

    @BeforeEach
    fun setUp() {
        every { repository.saveAndFlush(any<Pokemon>()) } answers { firstArg() }
    }

    @Test
    fun createsPokemonThatIsNotStoredYet() {
        every { repository.findByPokeApiId(25) } returns Optional.empty()

        val result = writer.upsert(PIKACHU)

        result.id shouldBe 25
        result.name shouldBe "pikachu"
        result.outcome shouldBe SyncOutcome.CREATED
        result.lastSyncedAt.shouldNotBeNull()
        val saved = slot<Pokemon>()
        verify(exactly = 1) { repository.saveAndFlush(capture(saved)) }
        saved.captured.stats.shouldContainExactly(PokemonStat("hp", 35))
        saved.captured.lastSyncedAt shouldBe result.lastSyncedAt
    }

    @Test
    fun updatesStoredPokemonMatchedByPokeApiIdIncludingRenames() {
        val stored = Pokemon(25, "pikachu-old", 4, 60, 112, null, listOf())
        every { repository.findByPokeApiId(25) } returns Optional.of(stored)

        val result = writer.upsert(PIKACHU)

        result.outcome shouldBe SyncOutcome.UPDATED
        result.name shouldBe "pikachu"
        stored.name shouldBe "pikachu"
        stored.stats.shouldContainExactly(PokemonStat("hp", 35))
        stored.speciesId shouldBe 25
        stored.lastSyncedAt shouldBe result.lastSyncedAt
        verify(exactly = 1) { repository.saveAndFlush(stored) }
    }

    @Test
    fun retriesAsUpdateWhenInsertedConcurrently() {
        val storedMeanwhile = Pokemon(25, "pikachu", 4, 60, 112, null, listOf())
        every { repository.findByPokeApiId(25) } returnsMany listOf(Optional.empty(), Optional.of(storedMeanwhile))
        every { repository.saveAndFlush(any<Pokemon>()) } throws DataIntegrityViolationException("duplicate") andThenAnswer
            { firstArg() }

        val result = writer.upsert(PIKACHU)

        result.outcome shouldBe SyncOutcome.UPDATED
        verify(exactly = 2) { repository.saveAndFlush(any<Pokemon>()) }
    }

    private companion object {
        val PIKACHU = PokeApiPokemon(
            25, "pikachu", 4, 60, 112, null, listOf(), listOf(), listOf(StatSlot(35, NamedResource("hp", null))),
            NamedResource("pikachu", "https://pokeapi.co/api/v2/pokemon-species/25/"),
        )
    }
}
