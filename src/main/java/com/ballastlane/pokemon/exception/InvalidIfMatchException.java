package com.ballastlane.pokemon.exception;

public class InvalidIfMatchException extends RuntimeException {

	public InvalidIfMatchException() {
		super("'If-Match' must be '*' or a list of entity tags such as \"3\"");
	}

}
