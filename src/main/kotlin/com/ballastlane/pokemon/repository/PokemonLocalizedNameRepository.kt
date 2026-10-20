package com.ballastlane.pokemon.repository

import com.ballastlane.pokemon.domain.PokemonLocalizedName
import org.springframework.data.jpa.repository.JpaRepository

interface PokemonLocalizedNameRepository : JpaRepository<PokemonLocalizedName, Long> {

    fun findByPokemonId(pokemonId: Long): List<PokemonLocalizedName>
}
