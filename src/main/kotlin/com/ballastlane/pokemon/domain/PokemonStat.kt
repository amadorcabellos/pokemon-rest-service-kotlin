package com.ballastlane.pokemon.domain

import jakarta.persistence.Column
import jakarta.persistence.Embeddable

/**
 * One base stat of a pokemon, as reported by PokeAPI (e.g. `hp = 35`).
 */
@Embeddable
data class PokemonStat(
    @Column(name = "stat_name", nullable = false) val name: String,
    @Column(name = "base_value", nullable = false) val baseValue: Int,
)
