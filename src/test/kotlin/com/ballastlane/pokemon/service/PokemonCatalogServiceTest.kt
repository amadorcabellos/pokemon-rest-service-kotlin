package com.ballastlane.pokemon.service

import com.ballastlane.pokemon.client.PokeApiClient
import com.ballastlane.pokemon.client.dto.PokeApiPokemonList
import com.ballastlane.pokemon.client.dto.PokeApiPokemonList.Entry
import com.ballastlane.pokemon.exception.PokeApiException
import com.ballastlane.pokemon.repository.PokemonRepository
import com.ballastlane.pokemon.web.dto.PokemonSummary
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.math.BigDecimal
import java.util.concurrent.Executors
import org.junit.jupiter.api.Test
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest

class PokemonCatalogServiceTest {

    private val pokeApiClient = mockk<PokeApiClient>()

    private val summaryLoader = mockk<PokemonSummaryLoader>()

    private val pokemonRepository = mockk<PokemonRepository>()

    private val service = PokemonCatalogService(
        pokeApiClient, summaryLoader, pokemonRepository, Executors.newVirtualThreadPerTaskExecutor(),
    )

    @Test
    fun buildsPageFromListingAndLoadedEntries() {
        every { pokeApiClient.fetchPokemonPage(0, 2) } returns listing(1302, 1, 2)
        every { summaryLoader.load(1) } returns summary(1, "bulbasaur")
        every { summaryLoader.load(2) } returns summary(2, "ivysaur")

        val page = service.getPage(0, 2)

        page.content.map { it.name }.shouldContainExactly("bulbasaur", "ivysaur")
        page.page shouldBe 0
        page.size shouldBe 2
        page.totalElements shouldBe 1302
        page.totalPages shouldBe 651
    }

    @Test
    fun usesOffsetFromPageAndSizeAndHandlesPartialLastPage() {
        every { pokeApiClient.fetchPokemonPage(20, 10) } returns listing(21, 21)
        every { summaryLoader.load(21) } returns summary(21, "spearow")

        val page = service.getPage(2, 10)

        page.content shouldHaveSize 1
        page.totalPages shouldBe 3
    }

    @Test
    fun returnsEmptyPagePastTheEnd() {
        every { pokeApiClient.fetchPokemonPage(2000, 20) } returns listing(1302)

        val page = service.getPage(100, 20)

        page.content.shouldBeEmpty()
        page.totalElements shouldBe 1302
        page.totalPages shouldBe 66
        verify(exactly = 0) { summaryLoader.load(any()) }
    }

    @Test
    fun onlyFetchesCountWhenOffsetOverflows() {
        every { pokeApiClient.fetchPokemonPage(0, 1) } returns listing(1302, 1)

        val page = service.getPage(Int.MAX_VALUE, 50)

        page.content.shouldBeEmpty()
        page.totalElements shouldBe 1302
        verify(exactly = 0) { summaryLoader.load(any()) }
    }

    @Test
    fun failsWholePageWithOriginalExceptionWhenAnEntryFails() {
        every { pokeApiClient.fetchPokemonPage(0, 2) } returns listing(1302, 1, 2)
        every { summaryLoader.load(1) } returns summary(1, "bulbasaur")
        every { summaryLoader.load(2) } throws PokeApiException("boom", RuntimeException())

        shouldThrow<PokeApiException> { service.getPage(0, 2) }.message shouldBe "boom"
    }

    @Test
    fun propagatesOpenCircuitFromEntryLoading() {
        every { pokeApiClient.fetchPokemonPage(0, 1) } returns listing(1302, 1)
        every { summaryLoader.load(1) } throws
            CallNotPermittedException.createCallNotPermittedException(CircuitBreaker.ofDefaults("pokeapi"))

        shouldThrow<CallNotPermittedException> { service.getPage(0, 1) }
    }

    @Test
    fun searchesStoredPokemonByNameAndLoadsTheirEntries() {
        every { pokemonRepository.findPokeApiIdsByNameLike("%chu%", PageRequest.of(1, 2)) } returns
            PageImpl(listOf(172), PageRequest.of(1, 2), 3)
        every { summaryLoader.load(172) } returns summary(172, "pichu")

        val page = service.searchByName("chu", 1, 2)

        page.content.map { it.name }.shouldContainExactly("pichu")
        page.page shouldBe 1
        page.totalElements shouldBe 3
        page.totalPages shouldBe 2
        verify(exactly = 0) { pokeApiClient.fetchPokemonPage(any(), any()) }
    }

    @Test
    fun escapesLikeWildcardsInSearchedName() {
        every { pokemonRepository.findPokeApiIdsByNameLike("%a\\_b\\%c\\\\%", PageRequest.of(0, 20)) } returns
            PageImpl(listOf(), PageRequest.of(0, 20), 0)

        val page = service.searchByName("a_b%c\\", 0, 20)

        page.content.shouldBeEmpty()
        page.totalElements shouldBe 0
    }

    private companion object {

        fun listing(count: Int, vararg ids: Int) = PokeApiPokemonList(
            count, ids.map { Entry("pokemon-$it", "https://pokeapi.co/api/v2/pokemon/$it/") },
        )

        fun summary(id: Int, name: String) = PokemonSummary(id, name, null, listOf("normal"), BigDecimal.ONE, listOf())
    }
}
