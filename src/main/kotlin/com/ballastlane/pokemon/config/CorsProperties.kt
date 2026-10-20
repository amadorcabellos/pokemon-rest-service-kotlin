package com.ballastlane.pokemon.config

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/**
 * Browser origins allowed to call the API (`app.cors.*`).
 *
 * @property allowedOrigins exact origins (scheme, host and port, no trailing slash); empty disables CORS
 * @property maxAge how long a browser may cache a preflight response
 */
@JvmRecord
@Validated
@ConfigurationProperties(prefix = "app.cors")
final data class CorsProperties(
    val allowedOrigins: List<String> = listOf("http://localhost:3000"),
    val maxAge: Duration = Duration.ofMinutes(30),
)
