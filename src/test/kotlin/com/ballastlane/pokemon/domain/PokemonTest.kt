package com.ballastlane.pokemon.domain

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PokemonTest {

    @Test
    fun copyUpstreamFromReplacesUpstreamFieldsOnly() {
        val stored = Pokemon(
            25, "pikachu-old", 4, 60, 112, "old.png", listOf("electric"),
            listOf(PokemonStat("hp", 30)), listOf(PokemonAbility("static", false)), null, null,
        )
        stored.onCreate()
        val createdAt = stored.createdAt
        val upstream = Pokemon(
            999, "pikachu", 5, 61, 113, "new.png", listOf("electric", "fairy"),
            listOf(PokemonStat("hp", 35), PokemonStat("speed", 90)),
            listOf(PokemonAbility("lightning-rod", true)), 25, "pikachu",
        )

        stored.copyUpstreamFrom(upstream)

        stored.pokeApiId shouldBe 25
        stored.createdAt shouldBe createdAt
        stored.name shouldBe "pikachu"
        stored.height shouldBe 5
        stored.weight shouldBe 61
        stored.baseExperience shouldBe 113
        stored.spriteUrl shouldBe "new.png"
        stored.types.shouldContainExactly("electric", "fairy")
        stored.stats.shouldContainExactly(PokemonStat("hp", 35), PokemonStat("speed", 90))
        stored.abilities.shouldContainExactly(PokemonAbility("lightning-rod", true))
        stored.speciesId shouldBe 25
        stored.speciesName shouldBe "pikachu"
    }

    @Test
    fun tagsAreStoredInLowercase() {
        val pokemon = Pokemon(25, "pikachu", 4, 60, 112, null, emptyList())

        PokemonTag(pokemon, "  Starter-Kanto ").tag shouldBe "starter-kanto"
    }
}
