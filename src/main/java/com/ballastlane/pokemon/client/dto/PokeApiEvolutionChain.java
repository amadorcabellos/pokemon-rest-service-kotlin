package com.ballastlane.pokemon.client.dto;

import com.ballastlane.pokemon.client.dto.PokeApiPokemon.NamedResource;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Subset of the PokeAPI {@code /evolution-chain/{id}} payload that this service uses.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PokeApiEvolutionChain(int id, ChainLink chain) {

	/**
	 * One species in the chain, how it evolves from its parent, and what it evolves into.
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ChainLink(
			NamedResource species,
			@JsonProperty("evolution_details") List<EvolutionDetail> evolutionDetails,
			@JsonProperty("evolves_to") List<ChainLink> evolvesTo) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record EvolutionDetail(
			NamedResource trigger,
			@JsonProperty("min_level") Integer minLevel,
			NamedResource item,
			@JsonProperty("held_item") NamedResource heldItem,
			@JsonProperty("min_happiness") Integer minHappiness,
			@JsonProperty("time_of_day") String timeOfDay) {
	}

}
