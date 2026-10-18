package com.ballastlane.poke.auth;

import java.time.Instant;

/**
 * What sign-in and refresh return. The refresh token is only ever shown here; the database keeps its hash.
 */
public record TokenPair(String accessToken, Instant accessTokenExpiresAt, String refreshToken,
		Instant refreshTokenExpiresAt) {

	@Override
	public String toString() {
		// Never print the tokens themselves, even by accident.
		return "TokenPair[accessTokenExpiresAt=%s, refreshTokenExpiresAt=%s]".formatted(accessTokenExpiresAt,
				refreshTokenExpiresAt);
	}

}
