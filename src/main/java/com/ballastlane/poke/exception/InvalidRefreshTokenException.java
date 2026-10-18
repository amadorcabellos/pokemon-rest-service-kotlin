package com.ballastlane.poke.exception;

/**
 * The refresh token is unknown, expired, already used, or its session has ended: one message for every case.
 */
public class InvalidRefreshTokenException extends RuntimeException {

	public InvalidRefreshTokenException() {
		super("The refresh token is invalid or has expired; sign in again");
	}

}
