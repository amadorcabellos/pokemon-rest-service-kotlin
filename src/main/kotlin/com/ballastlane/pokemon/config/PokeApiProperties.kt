package com.ballastlane.pokemon.config

import jakarta.validation.constraints.NotNull
import java.net.URI
import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/**
 * Connection settings for the upstream PokeAPI (`pokeapi.*`).
 *
 * @property baseUrl required; nullable only so that a missing value fails validation with a clear message
 */
@Validated
@ConfigurationProperties(prefix = "pokeapi")
data class PokeApiProperties(
    @field:NotNull val baseUrl: URI? = null,
    val connectTimeout: Duration = Duration.ofSeconds(5),
    val readTimeout: Duration = Duration.ofSeconds(10),
)
