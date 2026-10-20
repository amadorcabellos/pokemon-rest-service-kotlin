package com.ballastlane.pokemon.repository

import com.ballastlane.pokemon.domain.PokemonGeoMetadata
import org.springframework.data.jpa.repository.JpaRepository

interface PokemonGeoMetadataRepository : JpaRepository<PokemonGeoMetadata, Long> {

    fun findByPokemonId(pokemonId: Long): List<PokemonGeoMetadata>
}
