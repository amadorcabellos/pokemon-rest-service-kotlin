package com.ballastlane.pokemon.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Validated by {@code PasswordPolicy}, so every problem is reported together. Roles can't be requested.
 */
public record RegisterRequest(
		@Schema(description = "3 to 50 characters: letters, digits, '.', '_' and '-'; case-insensitive",
				example = "ash") String username,
		@Schema(description = "12 to 128 characters, any characters; not the username",
				example = "pikachu-i-choose-you", format = "password") String password) {

	@Override
	public String toString() {
		return "RegisterRequest[username=%s]".formatted(username);
	}

}
