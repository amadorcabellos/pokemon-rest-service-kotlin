package com.ballastlane.poke.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * One ability of a pokemon, as reported by PokeAPI.
 */
@Embeddable
public record PokemonAbility(
		@Column(name = "ability_name", nullable = false) String name,
		@Column(nullable = false) boolean hidden) {
}
