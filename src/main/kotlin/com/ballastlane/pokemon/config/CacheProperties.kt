package com.ballastlane.pokemon.config

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Application cache settings (`app.cache.*`).
 */
@ConfigurationProperties(prefix = "app.cache")
data class CacheProperties(val pokemonTtl: Duration = Duration.ofHours(1))
