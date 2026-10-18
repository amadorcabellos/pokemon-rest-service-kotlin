package com.ballastlane.poke.exception;

import java.time.Duration;

public class TooManyLoginAttemptsException extends RuntimeException {

	private final Duration retryAfter;

	public TooManyLoginAttemptsException(Duration retryAfter) {
		super("Too many failed sign-in attempts; try again later");
		this.retryAfter = retryAfter;
	}

	public Duration getRetryAfter() {
		return retryAfter;
	}

}
