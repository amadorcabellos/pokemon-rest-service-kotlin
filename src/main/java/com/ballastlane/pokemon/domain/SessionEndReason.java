package com.ballastlane.pokemon.domain;

/**
 * Why an {@link AuthSession} ended before it expired.
 */
public enum SessionEndReason {

	/** The client signed out. */
	LOGOUT,

	/** A refresh token that had already been used was presented again: possibly stolen. */
	REUSE

}
