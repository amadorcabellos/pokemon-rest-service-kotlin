package com.ballastlane.pokemon.exception;

public class UsernameTakenException extends RuntimeException {

	public UsernameTakenException(String username) {
		super("The username '%s' is already taken".formatted(username));
	}

}
