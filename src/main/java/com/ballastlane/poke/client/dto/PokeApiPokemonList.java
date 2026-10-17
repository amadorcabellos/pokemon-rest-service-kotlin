package com.ballastlane.poke.client.dto;

import com.ballastlane.poke.client.PokeApiUrls;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Subset of the PokeAPI {@code /pokemon?offset&limit} payload: the catalogue size and one page of entries.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PokeApiPokemonList(int count, List<Entry> results) {

	public PokeApiPokemonList {
		results = results != null ? List.copyOf(results) : List.of();
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Entry(String name, String url) {

		/**
		 * PokeAPI id, taken from the last path segment of the detail URL (e.g. {@code .../pokemon/25/}).
		 */
		public int id() {
			return PokeApiUrls.idFrom(url);
		}

	}

}
