package com.ballastlane.pokemon.service

import com.ballastlane.pokemon.client.PokeApiClient
import com.ballastlane.pokemon.client.dto.PokeApiEvolutionChain
import com.ballastlane.pokemon.client.dto.PokeApiEvolutionChain.ChainLink
import com.ballastlane.pokemon.client.dto.PokeApiPokemon.NamedResource
import com.ballastlane.pokemon.exception.PokeApiException
import com.ballastlane.pokemon.mapper.PokemonDetailsMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

class EvolutionChainLoaderTest {

    private val pokeApiClient = mockk<PokeApiClient>()

    private val loader = EvolutionChainLoader(pokeApiClient, PokemonDetailsMapper())

    @Test
    fun fetchesAndMapsChainWithoutCurrentStage() {
        every { pokeApiClient.fetchEvolutionChain(53) } returns PokeApiEvolutionChain(
            53,
            ChainLink(NamedResource("tauros", "https://pokeapi.co/api/v2/pokemon-species/128/"), listOf(), listOf()),
        )

        val stage = loader.load(53)

        stage.id shouldBe 128
        stage.name shouldBe "tauros"
        stage.current shouldBe false
    }

    @Test
    fun propagatesClientErrors() {
        every { pokeApiClient.fetchEvolutionChain(53) } throws PokeApiException("boom", RuntimeException())

        shouldThrow<PokeApiException> { loader.load(53) }
    }
}
