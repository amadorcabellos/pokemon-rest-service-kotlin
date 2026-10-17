package com.ballastlane.poke.exception;

/**
 * Raised when PokeAPI is unreachable or answers with an unexpected error.
 */
public class PokeApiException extends RuntimeException {

	public PokeApiException(String message, Throwable cause) {
		super(message, cause);
	}

}
