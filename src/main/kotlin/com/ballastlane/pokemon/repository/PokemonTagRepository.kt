package com.ballastlane.pokemon.repository

import com.ballastlane.pokemon.domain.PokemonTag
import org.springframework.data.jpa.repository.JpaRepository

interface PokemonTagRepository : JpaRepository<PokemonTag, Long> {

    fun findByPokemonId(pokemonId: Long): List<PokemonTag>
}
