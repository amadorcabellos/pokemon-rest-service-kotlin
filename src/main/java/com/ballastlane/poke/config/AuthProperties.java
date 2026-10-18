package com.ballastlane.poke.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Settings for users, tokens and sessions ({@code app.security.*}).
 *
 * @param jwtSecret HMAC key that signs access tokens; at least 32 bytes. There is no default, so the application
 * refuses to start without one
 * @param accessTokenTtl how long an access token is valid
 * @param refreshTokenTtl how long a refresh token is valid; every refresh issues a new one
 * @param maxSessionLifetime how long a session can be kept alive by refreshing, counted from sign-in
 * @param loginAttempts limit on failed sign-ins per username
 * @param initialAdmin administrator created at startup when both fields are set and the user doesn't exist
 */
@Validated
@ConfigurationProperties(prefix = "app.security")
public record AuthProperties(
		@NotBlank String jwtSecret,
		@DefaultValue("15m") Duration accessTokenTtl,
		@DefaultValue("7d") Duration refreshTokenTtl,
		@DefaultValue("30d") Duration maxSessionLifetime,
		@DefaultValue @Valid LoginAttempts loginAttempts,
		@DefaultValue InitialAdmin initialAdmin) {

	/** HS256 needs a key of at least 256 bits. */
	public static final int MIN_SECRET_BYTES = 32;

	public AuthProperties {
		if (jwtSecret != null && !jwtSecret.isBlank()
				&& jwtSecret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
			throw new IllegalArgumentException(
					"app.security.jwt-secret (JWT_SECRET) must be at least %d bytes".formatted(MIN_SECRET_BYTES));
		}
	}

	/**
	 * @param maxFailures failed sign-ins within {@code window} after which the username is locked
	 * @param window how far back failures are counted
	 * @param lockout how long a locked username gets 429
	 */
	public record LoginAttempts(
			@DefaultValue("5") @Min(1) int maxFailures,
			@DefaultValue("15m") Duration window,
			@DefaultValue("15m") Duration lockout) {
	}

	public record InitialAdmin(String username, String password) {

		public boolean isConfigured() {
			return username != null && !username.isBlank() && password != null && !password.isEmpty();
		}

	}

}
