package com.ballastlane.poke.web.dto;

import java.util.List;

/**
 * Detailed view of a pokemon: image, base stats, Pokedex description and evolution chain.
 */
public record PokemonDetails(
		Integer id,
		String name,
		String imageUrl,
		List<Stat> stats,
		int totalStats,
		String description,
		EvolutionStage evolution) {
}
