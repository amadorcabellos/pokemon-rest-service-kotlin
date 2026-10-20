package com.ballastlane.pokemon.web.dto

/**
 * One stage of an evolution chain (a species), how it evolves from the previous stage, and the stages it evolves
 * into.
 *
 * @property evolvesVia summary of the evolution condition (e.g. "level 16"); `null` for the base stage
 * @property current whether this is the species of the requested pokemon
 */
@JvmRecord
data class EvolutionStage(
    val id: Int,
    val name: String?,
    val evolvesVia: String?,
    val current: Boolean,
    val evolvesTo: List<EvolutionStage>,
) {

    /**
     * Returns a copy of this tree with `current` set only on the stage for the given species.
     */
    fun markCurrent(speciesId: Int): EvolutionStage =
        copy(current = id == speciesId, evolvesTo = evolvesTo.map { it.markCurrent(speciesId) })
}
