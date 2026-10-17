package com.ballastlane.poke.web.dto;

import java.util.List;

/**
 * One stage of an evolution chain (a species), how it evolves from the previous stage, and the stages it
 * evolves into.
 *
 * @param evolvesVia summary of the evolution condition (e.g. "level 16"); {@code null} for the base stage
 * @param current whether this is the species of the requested pokemon
 */
public record EvolutionStage(
		Integer id,
		String name,
		String evolvesVia,
		boolean current,
		List<EvolutionStage> evolvesTo) {

	/**
	 * Returns a copy of this tree with {@code current} set only on the stage for the given species.
	 */
	public EvolutionStage markCurrent(int speciesId) {
		return new EvolutionStage(id, name, evolvesVia, id == speciesId,
				evolvesTo.stream().map(stage -> stage.markCurrent(speciesId)).toList());
	}

}
