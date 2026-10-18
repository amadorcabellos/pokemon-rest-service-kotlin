package com.ballastlane.pokemon.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record LoginRequest(
		@Schema(example = "ash") String username,
		@Schema(example = "pikachu-i-choose-you", format = "password") String password) {

	@Override
	public String toString() {
		return "LoginRequest[username=%s]".formatted(username);
	}

}
