package com.ballastlane.pokemon.mapper

import com.ballastlane.pokemon.client.PokeApiUrls
import com.ballastlane.pokemon.client.dto.PokeApiEvolutionChain
import com.ballastlane.pokemon.client.dto.PokeApiEvolutionChain.ChainLink
import com.ballastlane.pokemon.client.dto.PokeApiEvolutionChain.EvolutionDetail
import com.ballastlane.pokemon.client.dto.PokeApiPokemon
import com.ballastlane.pokemon.client.dto.PokeApiSpecies
import com.ballastlane.pokemon.text.javaTrim
import com.ballastlane.pokemon.text.isJavaBlank
import com.ballastlane.pokemon.web.dto.EvolutionStage
import com.ballastlane.pokemon.web.dto.PokemonDetails
import com.ballastlane.pokemon.web.dto.Stat
import org.springframework.stereotype.Component

/**
 * Builds the detailed pokemon view from the PokeAPI pokemon, species and evolution chain resources.
 */
@Component
class PokemonDetailsMapper {

    /**
     * Assembles the view; the evolution tree is marked with the requested pokemon's species as current.
     */
    fun toDetails(pokemon: PokeApiPokemon, species: PokeApiSpecies, evolution: EvolutionStage): PokemonDetails {
        val stats = pokemon.stats.orEmpty().map { slot -> Stat(checkNotNull(slot.stat).name, slot.baseStat) }
        val totalStats = stats.sumOf { it.value }

        return PokemonDetails(
            pokemon.id, pokemon.name, imageUrl(pokemon), stats, totalStats, description(species),
            evolution.markCurrent(species.id),
        )
    }

    /**
     * Converts a PokeAPI evolution chain into a tree of stages, none marked as current.
     */
    fun toEvolutionStage(chain: PokeApiEvolutionChain): EvolutionStage = toStage(checkNotNull(chain.chain))

    /**
     * Fallback tree for a species without an evolution chain.
     */
    fun singleStage(species: PokeApiSpecies): EvolutionStage =
        EvolutionStage(species.id, species.name, null, false, emptyList())

    internal companion object {

        private const val ENGLISH = "en"

        /**
         * Official artwork when available, otherwise the default front sprite.
         */
        fun imageUrl(pokemon: PokeApiPokemon): String? {
            val sprites = pokemon.sprites ?: return null
            return sprites.other?.officialArtwork?.frontDefault ?: sprites.frontDefault
        }

        /**
         * English flavor text from the most recent game version (highest version id), cleaned into a single line.
         */
        fun description(species: PokeApiSpecies): String? = species.flavorTextEntries.orEmpty()
            .filter { it.language?.name == ENGLISH }
            .mapNotNull { entry ->
                val text = entry.flavorText
                val version = entry.version
                if (text != null && version != null) text to version else null
            }
            .maxByOrNull { (_, version) -> PokeApiUrls.idFrom(checkNotNull(version.url)) }
            ?.let { (text, _) -> clean(text) }

        fun clean(flavorText: String): String = flavorText
            .replace("­\n", "")
            .replace("­", "")
            .replace(Regex("[\\f\\r\\n]"), " ")
            .replace(Regex("\\s+"), " ")
            .javaTrim()

        /**
         * Short summary of how a stage evolves (e.g. "level 16", "use thunder-stone"); distinct conditions from
         * different games are joined with " or ". Returns `null` when there are no conditions (base stage).
         */
        fun summarize(details: List<EvolutionDetail>?): String? = details.orEmpty()
            .mapNotNull(::summarize)
            .distinct()
            .joinToString(" or ")
            .ifEmpty { null }

        private fun summarize(detail: EvolutionDetail): String? = when (val trigger = detail.trigger?.name) {
            null -> null
            "level-up" -> when {
                detail.minLevel != null -> "level ${detail.minLevel}"
                else -> (if (detail.minHappiness != null) "friendship" else "level up") + timeOfDay(detail)
            }
            "use-item" -> detail.item?.let { "use ${it.name}" } ?: "use item"
            "trade" -> detail.heldItem?.let { "trade holding ${it.name}" } ?: "trade"
            else -> trigger
        }

        private fun timeOfDay(detail: EvolutionDetail): String {
            val time = detail.timeOfDay
            return if (time != null && !time.isJavaBlank()) " ($time)" else ""
        }

        private fun toStage(link: ChainLink): EvolutionStage {
            val species = checkNotNull(link.species)
            return EvolutionStage(
                PokeApiUrls.idFrom(checkNotNull(species.url)), species.name, summarize(link.evolutionDetails), false,
                link.evolvesTo.orEmpty().map(::toStage),
            )
        }
    }
}
