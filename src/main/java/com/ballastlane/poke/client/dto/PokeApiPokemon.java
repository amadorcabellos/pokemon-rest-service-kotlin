package com.ballastlane.poke.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Subset of the PokeAPI {@code /pokemon/{idOrName}} payload that this service uses.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PokeApiPokemon(
		int id,
		String name,
		int height,
		int weight,
		@JsonProperty("base_experience") Integer baseExperience,
		Sprites sprites,
		List<TypeSlot> types) {

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Sprites(@JsonProperty("front_default") String frontDefault) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record TypeSlot(int slot, NamedResource type) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record NamedResource(String name, String url) {
	}

}
