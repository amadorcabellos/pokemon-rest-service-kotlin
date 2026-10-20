package com.ballastlane.pokemon.mapper

import com.ballastlane.pokemon.client.dto.PokeApiEvolutionChain
import com.ballastlane.pokemon.client.dto.PokeApiEvolutionChain.ChainLink
import com.ballastlane.pokemon.client.dto.PokeApiEvolutionChain.EvolutionDetail
import com.ballastlane.pokemon.client.dto.PokeApiPokemon
import com.ballastlane.pokemon.client.dto.PokeApiPokemon.Artwork
import com.ballastlane.pokemon.client.dto.PokeApiPokemon.NamedResource
import com.ballastlane.pokemon.client.dto.PokeApiPokemon.OtherSprites
import com.ballastlane.pokemon.client.dto.PokeApiPokemon.Sprites
import com.ballastlane.pokemon.client.dto.PokeApiPokemon.StatSlot
import com.ballastlane.pokemon.client.dto.PokeApiSpecies
import com.ballastlane.pokemon.client.dto.PokeApiSpecies.FlavorText
import com.ballastlane.pokemon.web.dto.EvolutionStage
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PokemonDetailsMapperTest {

    private val mapper = PokemonDetailsMapper()

    @Test
    fun toDetailsMapsStatsTotalImageDescriptionAndMarksCurrentStage() {
        val pikachu = pokemon(
            Sprites("sprite.png", OtherSprites(Artwork("artwork.png"))),
            listOf(
                stat("hp", 35), stat("attack", 55), stat("defense", 40), stat("special-attack", 50),
                stat("special-defense", 50), stat("speed", 90),
            ),
        )
        val species = species(25, "pikachu", listOf(flavor("Pika pika.", "en", 1)))
        val chain = EvolutionStage(172, "pichu", null, false, listOf(EvolutionStage(25, "pikachu", "friendship", false, listOf())))

        val details = mapper.toDetails(pikachu, species, chain)

        details.imageUrl shouldBe "artwork.png"
        details.stats.map { it.name }
            .shouldContainExactly("hp", "attack", "defense", "special-attack", "special-defense", "speed")
        details.totalStats shouldBe 320
        details.description shouldBe "Pika pika."
        details.evolution.current shouldBe false
        details.evolution.evolvesTo[0].current shouldBe true
        // Local data is never part of the cached PokeAPI view.
        details.localData.shouldBeNull()
    }

    @Test
    fun imageFallsBackToSpriteThenNull() {
        PokemonDetailsMapper.imageUrl(pokemon(Sprites("sprite.png", OtherSprites(null)), listOf())) shouldBe "sprite.png"
        PokemonDetailsMapper.imageUrl(pokemon(Sprites("sprite.png", null), listOf())) shouldBe "sprite.png"
        PokemonDetailsMapper.imageUrl(pokemon(null, listOf())).shouldBeNull()
    }

    @Test
    fun descriptionUsesMostRecentEnglishVersionAndIsCleaned() {
        val species = species(
            25, "pikachu",
            listOf(
                flavor("Old\ntext.", "en", 1),
                flavor("ピカチュウ", "ja", 40),
                flavor("It can gen­\nerate\u000Cpower-\nful  electricity.\n", "en", 34),
            ),
        )

        PokemonDetailsMapper.description(species) shouldBe "It can generate power- ful electricity."
    }

    @Test
    fun descriptionIsNullWithoutEnglishText() {
        PokemonDetailsMapper.description(species(1, "x", listOf(flavor("Texte", "fr", 2)))).shouldBeNull()
        PokemonDetailsMapper.description(species(1, "x", null)).shouldBeNull()
    }

    @Test
    fun summarizesEvolutionConditions() {
        PokemonDetailsMapper.summarize(listOf(detail("level-up", 16, null, null, null, ""))) shouldBe "level 16"
        PokemonDetailsMapper.summarize(listOf(detail("use-item", null, "thunder-stone", null, null, ""))) shouldBe
            "use thunder-stone"
        PokemonDetailsMapper.summarize(listOf(detail("trade", null, null, "metal-coat", null, ""))) shouldBe
            "trade holding metal-coat"
        PokemonDetailsMapper.summarize(listOf(detail("trade", null, null, null, null, ""))) shouldBe "trade"
        PokemonDetailsMapper.summarize(listOf(detail("level-up", null, null, null, 160, "day"))) shouldBe
            "friendship (day)"
        PokemonDetailsMapper.summarize(listOf(detail("spin", null, null, null, null, ""))) shouldBe "spin"
        PokemonDetailsMapper.summarize(listOf()).shouldBeNull()
    }

    @Test
    fun joinsDistinctConditionsFromDifferentGames() {
        PokemonDetailsMapper.summarize(
            listOf(
                detail("level-up", null, null, null, 220, ""),
                detail("level-up", null, null, null, 160, ""),
                detail("use-item", null, "ice-stone", null, null, ""),
            ),
        ) shouldBe "friendship or use ice-stone"
    }

    @Test
    fun mapsLinearChain() {
        val root = mapper.toEvolutionStage(
            PokeApiEvolutionChain(
                10,
                link(
                    172, "pichu", listOf(),
                    listOf(
                        link(
                            25, "pikachu", listOf(detail("level-up", null, null, null, 220, "")),
                            listOf(link(26, "raichu", listOf(detail("use-item", null, "thunder-stone", null, null, "")), listOf())),
                        ),
                    ),
                ),
            ),
        )

        root.id shouldBe 172
        root.evolvesVia.shouldBeNull()
        val pikachu = root.evolvesTo[0]
        pikachu.evolvesVia shouldBe "friendship"
        pikachu.evolvesTo[0].evolvesVia shouldBe "use thunder-stone"
        pikachu.evolvesTo[0].evolvesTo.shouldBeEmpty()
    }

    @Test
    fun mapsBranchingChainInOrder() {
        val eevee = mapper.toEvolutionStage(
            PokeApiEvolutionChain(
                67,
                link(
                    133, "eevee", listOf(),
                    listOf(
                        link(134, "vaporeon", listOf(detail("use-item", null, "water-stone", null, null, "")), listOf()),
                        link(135, "jolteon", listOf(detail("use-item", null, "thunder-stone", null, null, "")), listOf()),
                        link(196, "espeon", listOf(detail("level-up", null, null, null, 160, "day")), listOf()),
                    ),
                ),
            ),
        )

        eevee.evolvesTo.map { it.name }.shouldContainExactly("vaporeon", "jolteon", "espeon")
        eevee.markCurrent(135).evolvesTo.map { it.current }.shouldContainExactly(false, true, false)
    }

    @Test
    fun mapsSingleStageChain() {
        val tauros = mapper.toEvolutionStage(PokeApiEvolutionChain(53, link(128, "tauros", listOf(), listOf())))

        tauros.evolvesTo.shouldBeEmpty()
        tauros.markCurrent(128).current shouldBe true
    }

    private companion object {

        const val API = "https://pokeapi.co/api/v2"

        fun pokemon(sprites: Sprites?, stats: List<StatSlot>) = PokeApiPokemon(
            25, "pikachu", 4, 60, 112, sprites, listOf(), listOf(), stats,
            NamedResource("pikachu", "$API/pokemon-species/25/"),
        )

        fun stat(name: String, value: Int) = StatSlot(value, NamedResource(name, null))

        fun species(id: Int, name: String, texts: List<FlavorText>?) = PokeApiSpecies(id, name, texts, null)

        fun flavor(text: String, language: String, versionId: Int) = FlavorText(
            text, NamedResource(language, null), NamedResource("v$versionId", "$API/version/$versionId/"),
        )

        fun detail(
            trigger: String,
            minLevel: Int?,
            item: String?,
            heldItem: String?,
            minHappiness: Int?,
            timeOfDay: String,
        ) = EvolutionDetail(
            NamedResource(trigger, null), minLevel, item?.let { NamedResource(it, null) },
            heldItem?.let { NamedResource(it, null) }, minHappiness, timeOfDay,
        )

        fun link(id: Int, name: String, details: List<EvolutionDetail>, next: List<ChainLink>) =
            ChainLink(NamedResource(name, "$API/pokemon-species/$id/"), details, next)
    }
}
