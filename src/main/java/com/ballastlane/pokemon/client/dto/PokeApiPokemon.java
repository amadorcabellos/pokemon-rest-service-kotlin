package com.ballastlane.pokemon.client.dto;

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
		List<TypeSlot> types,
		List<AbilitySlot> abilities,
		List<StatSlot> stats,
		NamedResource species) {

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Sprites(@JsonProperty("front_default") String frontDefault, OtherSprites other) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record OtherSprites(@JsonProperty("official-artwork") Artwork officialArtwork) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Artwork(@JsonProperty("front_default") String frontDefault) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record StatSlot(@JsonProperty("base_stat") int baseStat, NamedResource stat) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record TypeSlot(int slot, NamedResource type) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record AbilitySlot(int slot, @JsonProperty("is_hidden") boolean hidden, NamedResource ability) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record NamedResource(String name, String url) {
	}

}
