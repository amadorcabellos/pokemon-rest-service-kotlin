package com.ballastlane.poke.web.dto;

import com.ballastlane.poke.auth.TokenPair;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * Tokens from sign-in and refresh. Send {@code accessToken} as {@code Authorization: Bearer ...}; keep
 * {@code refreshToken} to get the next pair before it expires.
 */
public record TokenResponse(
		String accessToken,
		@Schema(example = "Bearer") String tokenType,
		Instant expiresAt,
		String refreshToken,
		Instant refreshExpiresAt) {

	public static TokenResponse from(TokenPair pair) {
		return new TokenResponse(pair.accessToken(), "Bearer", pair.accessTokenExpiresAt(), pair.refreshToken(),
				pair.refreshTokenExpiresAt());
	}

	@Override
	public String toString() {
		return "TokenResponse[expiresAt=%s, refreshExpiresAt=%s]".formatted(expiresAt, refreshExpiresAt);
	}

}
