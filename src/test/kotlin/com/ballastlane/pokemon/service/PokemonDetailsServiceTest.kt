package com.ballastlane.pokemon.service

import com.ballastlane.pokemon.client.PokeApiClient
import com.ballastlane.pokemon.client.dto.PokeApiPokemon
import com.ballastlane.pokemon.client.dto.PokeApiPokemon.NamedResource
import com.ballastlane.pokemon.client.dto.PokeApiSpecies
import com.ballastlane.pokemon.domain.Pokemon
import com.ballastlane.pokemon.exception.PokeApiException
import com.ballastlane.pokemon.exception.PokemonNotFoundException
import com.ballastlane.pokemon.mapper.PokemonDetailsMapper
import com.ballastlane.pokemon.mapper.PokemonMapper
import com.ballastlane.pokemon.repository.PokemonRepository
import com.ballastlane.pokemon.web.dto.EvolutionStage
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.Optional
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException

class PokemonDetailsServiceTest {

    private val pokeApiClient = mockk<PokeApiClient>()

    private val evolutionChainLoader = mockk<EvolutionChainLoader>()

    private val pokemonRepository = mockk<PokemonRepository>()

    private val service = PokemonDetailsService(
        pokeApiClient, evolutionChainLoader, pokemonRepository, PokemonMapper(), PokemonDetailsMapper(),
    )

    @BeforeEach
    fun setUp() {
        every { pokemonRepository.save(any<Pokemon>()) } answers { firstArg() }
    }

    @Test
    fun buildsViewFromPokemonSpeciesAndChainUsingTheLinkedSpecies() {
        every { pokeApiClient.fetchPokemon("deoxys-attack") } returns pokemon(10001, "deoxys-attack", 386)
        every { pokemonRepository.findByPokeApiId(10001) } returns Optional.empty()
        every { pokeApiClient.fetchSpecies(386) } returns species(386, "deoxys", 186)
        every { evolutionChainLoader.load(186) } returns DEOXYS_CHAIN

        val details = service.getPokemon(" Deoxys-Attack ")

        details.id shouldBe 10001
        details.name shouldBe "deoxys-attack"
        details.evolution.name shouldBe "deoxys"
        details.evolution.current shouldBe true
    }

    @Test
    fun savesNewPokemon() {
        stubHappyPath()
        every { pokemonRepository.findByPokeApiId(25) } returns Optional.empty()

        service.getPokemon("pikachu")

        verify(exactly = 1) { pokemonRepository.save(any<Pokemon>()) }
    }

    @Test
    fun skipsSavingAlreadyStoredPokemon() {
        stubHappyPath()
        every { pokemonRepository.findByPokeApiId(25) } returns
            Optional.of(Pokemon(25, "pikachu", 4, 60, 112, null, listOf()))

        service.getPokemon("pikachu")

        verify(exactly = 0) { pokemonRepository.save(any<Pokemon>()) }
    }

    @Test
    fun ignoresConcurrentDuplicateSave() {
        stubHappyPath()
        every { pokemonRepository.findByPokeApiId(25) } returns Optional.empty()
        every { pokemonRepository.save(any<Pokemon>()) } throws DataIntegrityViolationException("duplicate")

        service.getPokemon("pikachu").name shouldBe "pikachu"
    }

    @Test
    fun usesSingleStageWhenSpeciesHasNoChain() {
        every { pokeApiClient.fetchPokemon("pikachu") } returns pokemon(25, "pikachu", 25)
        every { pokemonRepository.findByPokeApiId(25) } returns Optional.empty()
        every { pokeApiClient.fetchSpecies(25) } returns PokeApiSpecies(25, "pikachu", listOf(), null)

        val details = service.getPokemon("pikachu")

        details.evolution.name shouldBe "pikachu"
        details.evolution.evolvesTo.shouldBeEmpty()
        verify(exactly = 0) { evolutionChainLoader.load(any()) }
    }

    @Test
    fun propagatesNotFound() {
        every { pokeApiClient.fetchPokemon("missingno") } throws PokemonNotFoundException("missingno")

        shouldThrow<PokemonNotFoundException> { service.getPokemon("missingno") }
        verify(exactly = 0) { pokemonRepository.save(any<Pokemon>()) }
    }

    @Test
    fun failsWholeRequestWhenSpeciesFails() {
        every { pokeApiClient.fetchPokemon("pikachu") } returns pokemon(25, "pikachu", 25)
        every { pokemonRepository.findByPokeApiId(25) } returns Optional.empty()
        every { pokeApiClient.fetchSpecies(25) } throws PokeApiException("boom", RuntimeException())

        shouldThrow<PokeApiException> { service.getPokemon("pikachu") }
        verify(exactly = 0) { evolutionChainLoader.load(any()) }
    }

    @Test
    fun failsWholeRequestWhenChainFails() {
        every { pokeApiClient.fetchPokemon("pikachu") } returns pokemon(25, "pikachu", 25)
        every { pokemonRepository.findByPokeApiId(25) } returns Optional.empty()
        every { pokeApiClient.fetchSpecies(25) } returns species(25, "pikachu", 10)
        every { evolutionChainLoader.load(10) } throws PokeApiException("boom", RuntimeException())

        shouldThrow<PokeApiException> { service.getPokemon("pikachu") }
    }

    private fun stubHappyPath() {
        every { pokeApiClient.fetchPokemon("pikachu") } returns pokemon(25, "pikachu", 25)
        every { pokeApiClient.fetchSpecies(25) } returns species(25, "pikachu", 10)
        every { evolutionChainLoader.load(10) } returns EvolutionStage(25, "pikachu", null, false, listOf())
    }

    private companion object {

        const val API = "https://pokeapi.co/api/v2"

        val DEOXYS_CHAIN = EvolutionStage(386, "deoxys", null, false, listOf())

        fun pokemon(id: Int, name: String, speciesId: Int) = PokeApiPokemon(
            id, name, 4, 60, 112, null, listOf(), listOf(), listOf(),
            NamedResource(name, "$API/pokemon-species/$speciesId/"),
        )

        fun species(id: Int, name: String, chainId: Int) =
            PokeApiSpecies(id, name, listOf(), PokeApiSpecies.Resource("$API/evolution-chain/$chainId/"))
    }
}
