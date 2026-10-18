package com.ballastlane.pokemon.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Browser origins allowed to call the API ({@code app.cors.*}).
 *
 * @param allowedOrigins exact origins (scheme, host and port, no trailing slash); empty disables CORS
 * @param maxAge how long a browser may cache a preflight response
 */
@Validated
@ConfigurationProperties(prefix = "app.cors")
public record CorsProperties(
		@DefaultValue("http://localhost:3000") List<String> allowedOrigins,
		@DefaultValue("30m") Duration maxAge) {
}
