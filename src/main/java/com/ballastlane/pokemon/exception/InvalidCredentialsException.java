package com.ballastlane.pokemon.exception;

/**
 * Wrong username or wrong password: deliberately the same exception, so the response never tells them apart.
 */
public class InvalidCredentialsException extends RuntimeException {

	public InvalidCredentialsException() {
		super("The username or password is incorrect");
	}

}
