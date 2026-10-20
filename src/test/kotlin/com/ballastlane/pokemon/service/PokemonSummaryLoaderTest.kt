package com.ballastlane.pokemon.service

import com.ballastlane.pokemon.client.PokeApiClient
import com.ballastlane.pokemon.client.dto.PokeApiPokemon
import com.ballastlane.pokemon.exception.PokeApiException
import com.ballastlane.pokemon.mapper.PokemonMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.bigdecimal.shouldBeEqualIgnoringScale
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.math.BigDecimal
import org.junit.jupiter.api.Test

class PokemonSummaryLoaderTest {

    private val pokeApiClient = mockk<PokeApiClient>()

    private val loader = PokemonSummaryLoader(pokeApiClient, PokemonMapper())

    @Test
    fun fetchesByIdAndMapsToSummary() {
        every { pokeApiClient.fetchPokemon("25") } returns PokeApiPokemon(
            25, "pikachu", 4, 60, 112,
            PokeApiPokemon.Sprites("sprite.png", null),
            listOf(PokeApiPokemon.TypeSlot(1, PokeApiPokemon.NamedResource("electric", null))),
            listOf(PokeApiPokemon.AbilitySlot(1, false, PokeApiPokemon.NamedResource("static", null))),
            null, null,
        )

        val summary = loader.load(25)

        summary.name shouldBe "pikachu"
        summary.category.shouldContainExactly("electric")
        summary.massKg.shouldBeEqualIgnoringScale(BigDecimal("6.0"))
    }

    @Test
    fun propagatesClientErrors() {
        every { pokeApiClient.fetchPokemon("25") } throws PokeApiException("boom", RuntimeException())

        shouldThrow<PokeApiException> { loader.load(25) }
    }
}
