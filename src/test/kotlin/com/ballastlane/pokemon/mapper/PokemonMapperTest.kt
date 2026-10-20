package com.ballastlane.pokemon.mapper

import com.ballastlane.pokemon.client.dto.PokeApiPokemon
import com.ballastlane.pokemon.client.dto.PokeApiPokemon.AbilitySlot
import com.ballastlane.pokemon.client.dto.PokeApiPokemon.NamedResource
import com.ballastlane.pokemon.client.dto.PokeApiPokemon.Sprites
import com.ballastlane.pokemon.client.dto.PokeApiPokemon.StatSlot
import com.ballastlane.pokemon.client.dto.PokeApiPokemon.TypeSlot
import com.ballastlane.pokemon.domain.PokemonAbility
import com.ballastlane.pokemon.domain.PokemonStat
import com.ballastlane.pokemon.web.dto.PokemonSummary
import com.ballastlane.pokemon.web.dto.Skill
import io.kotest.matchers.bigdecimal.shouldBeEqualIgnoringScale
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import org.junit.jupiter.api.Test

class PokemonMapperTest {

    private val mapper = PokemonMapper()

    @Test
    fun toEntityOrdersTypesBySlot() {
        val source = PokeApiPokemon(
            1, "bulbasaur", 7, 69, 64, Sprites("sprite.png", null),
            listOf(TypeSlot(2, NamedResource("poison", null)), TypeSlot(1, NamedResource("grass", null))),
            listOf(), null, null,
        )

        val entity = mapper.toEntity(source)

        entity.pokeApiId shouldBe 1
        entity.name shouldBe "bulbasaur"
        entity.spriteUrl shouldBe "sprite.png"
        entity.types.shouldContainExactly("grass", "poison")
    }

    @Test
    fun toEntityMapsStatsAbilitiesAndSpecies() {
        val source = PokeApiPokemon(
            25, "pikachu", 4, 60, 112, null, listOf(),
            listOf(
                AbilitySlot(3, true, NamedResource("lightning-rod", null)),
                AbilitySlot(1, false, NamedResource("static", null)),
            ),
            listOf(StatSlot(35, NamedResource("hp", null)), StatSlot(90, NamedResource("speed", null))),
            NamedResource("pikachu", "https://pokeapi.co/api/v2/pokemon-species/25/"),
        )

        val entity = mapper.toEntity(source)

        entity.stats.shouldContainExactly(PokemonStat("hp", 35), PokemonStat("speed", 90))
        entity.abilities.shouldContainExactly(PokemonAbility("static", false), PokemonAbility("lightning-rod", true))
        entity.speciesId shouldBe 25
        entity.speciesName shouldBe "pikachu"
        entity.lastSyncedAt.shouldBeNull()
    }

    @Test
    fun toEntityHandlesMissingOptionalFields() {
        val source = PokeApiPokemon(132, "ditto", 3, 40, null, null, null, null, null, null)

        val entity = mapper.toEntity(source)

        entity.spriteUrl.shouldBeNull()
        entity.baseExperience.shouldBeNull()
        entity.types.shouldBeEmpty()
        entity.stats.shouldBeEmpty()
        entity.abilities.shouldBeEmpty()
        entity.speciesId.shouldBeNull()
    }

    @Test
    fun toSummaryMapsCategoryMassAndSkills() {
        val source = PokeApiPokemon(
            1, "bulbasaur", 7, 69, 64, Sprites("sprite.png", null),
            listOf(TypeSlot(2, NamedResource("poison", null)), TypeSlot(1, NamedResource("grass", null))),
            listOf(
                AbilitySlot(3, true, NamedResource("chlorophyll", null)),
                AbilitySlot(1, false, NamedResource("overgrow", null)),
            ),
            null, null,
        )

        val summary = mapper.toSummary(source)

        summary shouldBe PokemonSummary(
            1, "bulbasaur", "sprite.png", listOf("grass", "poison"), BigDecimal("6.9"),
            listOf(Skill("overgrow", false), Skill("chlorophyll", true)),
        )
    }

    @Test
    fun toSummaryConvertsHectogramsToKilograms() {
        mapper.toSummary(PokeApiPokemon(25, "pikachu", 4, 60, 112, null, null, null, null, null)).massKg
            .shouldBeEqualIgnoringScale(BigDecimal("6.0"))
        mapper.toSummary(PokeApiPokemon(143, "snorlax", 21, 4600, 189, null, null, null, null, null)).massKg
            .shouldBeEqualIgnoringScale(BigDecimal("460.0"))
    }

    @Test
    fun toSummaryHandlesMissingSpriteTypesAndAbilities() {
        val summary = mapper.toSummary(PokeApiPokemon(132, "ditto", 3, 40, null, null, null, null, null, null))

        summary.spriteUrl.shouldBeNull()
        summary.category.shouldBeEmpty()
        summary.skills.shouldBeEmpty()
    }
}
