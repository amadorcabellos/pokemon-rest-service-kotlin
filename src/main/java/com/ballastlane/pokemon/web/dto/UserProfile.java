package com.ballastlane.pokemon.web.dto;

import com.ballastlane.pokemon.domain.AppUser;
import com.ballastlane.pokemon.domain.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * A user as the API shows it: never with the password.
 */
public record UserProfile(
		Long id,
		@Schema(example = "ash") String username,
		@Schema(example = "[\"USER\"]") List<String> roles,
		Instant createdAt) {

	public static UserProfile from(AppUser user) {
		return new UserProfile(user.getId(), user.getUsername(),
				user.getRoles().stream().map(Role::name).sorted().toList(), user.getCreatedAt());
	}

}
