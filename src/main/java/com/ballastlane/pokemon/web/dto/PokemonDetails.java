package com.ballastlane.pokemon.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Detailed view of a pokemon: image, base stats, Pokedex description and evolution chain, plus its local
 * (proprietary) data.
 *
 * @param localData always read fresh from PostgreSQL; {@code null} in the cached value, which only holds the
 * PokeAPI part
 */
public record PokemonDetails(
		Integer id,
		String name,
		String imageUrl,
		List<Stat> stats,
		int totalStats,
		String description,
		EvolutionStage evolution,
		@Schema(description = "Proprietary data owned by this service; changed with PUT or PATCH on this resource")
		LocalData localData) {

	/**
	 * The PokeAPI part only, as built and cached by the details service.
	 */
	public PokemonDetails(Integer id, String name, String imageUrl, List<Stat> stats, int totalStats,
			String description, EvolutionStage evolution) {
		this(id, name, imageUrl, stats, totalStats, description, evolution, null);
	}

	public PokemonDetails withLocalData(LocalData localData) {
		return new PokemonDetails(id, name, imageUrl, stats, totalStats, description, evolution, localData);
	}

}
