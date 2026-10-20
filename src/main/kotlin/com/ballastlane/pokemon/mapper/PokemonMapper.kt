package com.ballastlane.pokemon.mapper

import com.ballastlane.pokemon.client.PokeApiUrls
import com.ballastlane.pokemon.client.dto.PokeApiPokemon
import com.ballastlane.pokemon.domain.Pokemon
import com.ballastlane.pokemon.domain.PokemonAbility
import com.ballastlane.pokemon.domain.PokemonStat
import com.ballastlane.pokemon.web.dto.PokemonSummary
import com.ballastlane.pokemon.web.dto.Skill
import java.math.BigDecimal
import org.springframework.stereotype.Component

@Component
class PokemonMapper {

    /**
     * Builds the upstream data of a pokemon: stats in PokeAPI order, abilities in slot order and the linked species.
     */
    fun toEntity(source: PokeApiPokemon): Pokemon {
        val stats = source.stats.orEmpty().map { slot -> PokemonStat(checkNotNull(slot.stat?.name), slot.baseStat) }
        val abilities = source.abilitySlots().map { slot ->
            PokemonAbility(checkNotNull(slot.ability?.name), slot.hidden)
        }
        val species = source.species
        val speciesId = species?.url?.let(PokeApiUrls::idFrom)

        return Pokemon(
            source.id, checkNotNull(source.name), source.height, source.weight, source.baseExperience,
            source.spriteUrl(), source.typeNames().map { checkNotNull(it) }, stats, abilities, speciesId, species?.name,
        )
    }

    /**
     * Builds a catalogue entry: category is the pokemon's types and mass is converted from hectograms to kilograms.
     */
    fun toSummary(source: PokeApiPokemon): PokemonSummary {
        val skills = source.abilitySlots().map { slot -> Skill(checkNotNull(slot.ability).name, slot.hidden) }
        val massKg = BigDecimal.valueOf(source.weight.toLong()).movePointLeft(1)

        return PokemonSummary(source.id, source.name, source.spriteUrl(), source.typeNames(), massKg, skills)
    }

    private fun PokeApiPokemon.typeNames(): List<String?> =
        types.orEmpty().sortedBy { it.slot }.map { checkNotNull(it.type).name }

    private fun PokeApiPokemon.abilitySlots(): List<PokeApiPokemon.AbilitySlot> =
        abilities.orEmpty().sortedBy { it.slot }

    private fun PokeApiPokemon.spriteUrl(): String? = sprites?.frontDefault
}
