package com.ballastlane.pokemon.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * Settings for updating local (proprietary) pokemon data ({@code app.local-data.*}).
 *
 * @param maxRequestSize largest accepted body of a PUT or PATCH on a pokemon; larger requests get 413
 */
@Validated
@ConfigurationProperties(prefix = "app.local-data")
public record LocalDataProperties(@DefaultValue("64KB") DataSize maxRequestSize) {
}
