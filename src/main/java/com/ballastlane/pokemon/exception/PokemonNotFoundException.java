package com.ballastlane.pokemon.exception;

public class PokemonNotFoundException extends RuntimeException {

	public PokemonNotFoundException(String nameOrId) {
		super("Pokemon '%s' was not found".formatted(nameOrId));
	}

}
