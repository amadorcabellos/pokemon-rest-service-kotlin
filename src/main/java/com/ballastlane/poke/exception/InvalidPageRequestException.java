package com.ballastlane.poke.exception;

/**
 * Raised when the requested page number or page size is outside the allowed range.
 */
public class InvalidPageRequestException extends RuntimeException {

	public InvalidPageRequestException(String message) {
		super(message);
	}

}
