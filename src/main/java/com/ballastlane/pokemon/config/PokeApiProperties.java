package com.ballastlane.pokemon.config;

import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Connection settings for the upstream PokeAPI ({@code pokeapi.*}).
 */
@Validated
@ConfigurationProperties(prefix = "pokeapi")
public record PokeApiProperties(
		@NotNull URI baseUrl,
		@DefaultValue("5s") Duration connectTimeout,
		@DefaultValue("10s") Duration readTimeout) {
}
