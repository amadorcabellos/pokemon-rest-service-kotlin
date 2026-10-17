package com.ballastlane.poke.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Application cache settings ({@code app.cache.*}).
 */
@ConfigurationProperties(prefix = "app.cache")
public record CacheProperties(@DefaultValue("1h") Duration pokemonTtl) {
}
