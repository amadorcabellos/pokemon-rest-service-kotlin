package com.ballastlane.poke.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * One base stat of a pokemon, as reported by PokeAPI (e.g. {@code hp = 35}).
 */
@Embeddable
public record PokemonStat(
		@Column(name = "stat_name", nullable = false) String name,
		@Column(name = "base_value", nullable = false) int baseValue) {
}
