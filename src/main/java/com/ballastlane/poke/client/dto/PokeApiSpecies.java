package com.ballastlane.poke.client.dto;

import com.ballastlane.poke.client.dto.PokeApiPokemon.NamedResource;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Subset of the PokeAPI {@code /pokemon-species/{id}} payload that this service uses.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PokeApiSpecies(
		int id,
		String name,
		@JsonProperty("flavor_text_entries") List<FlavorText> flavorTextEntries,
		@JsonProperty("evolution_chain") Resource evolutionChain) {

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record FlavorText(@JsonProperty("flavor_text") String flavorText, NamedResource language,
			NamedResource version) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Resource(String url) {
	}

}
