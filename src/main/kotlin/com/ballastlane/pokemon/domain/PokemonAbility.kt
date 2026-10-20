package com.ballastlane.pokemon.domain

import jakarta.persistence.Column
import jakarta.persistence.Embeddable

/**
 * One ability of a pokemon, as reported by PokeAPI.
 */
@Embeddable
data class PokemonAbility(
    @Column(name = "ability_name", nullable = false) val name: String,
    @Column(nullable = false) val hidden: Boolean,
)
