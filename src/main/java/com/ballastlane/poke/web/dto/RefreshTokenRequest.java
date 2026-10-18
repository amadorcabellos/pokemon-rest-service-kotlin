package com.ballastlane.poke.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Body of refresh and sign-out: the refresh token is the only credential they need.
 */
public record RefreshTokenRequest(
		@Schema(description = "The refresh token from sign-in or the last refresh") String refreshToken) {

	@Override
	public String toString() {
		return "RefreshTokenRequest[refreshToken=<hidden>]";
	}

}
