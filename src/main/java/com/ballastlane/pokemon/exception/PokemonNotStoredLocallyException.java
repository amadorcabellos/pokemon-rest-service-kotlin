package com.ballastlane.pokemon.exception;

/**
 * The pokemon is not in the local database, so its local data can't be updated (it may still exist in PokeAPI).
 */
public class PokemonNotStoredLocallyException extends RuntimeException {

	public PokemonNotStoredLocallyException(String nameOrId) {
		super("Pokemon '%s' is not stored locally; look it up or synchronize it first".formatted(nameOrId));
	}

}
