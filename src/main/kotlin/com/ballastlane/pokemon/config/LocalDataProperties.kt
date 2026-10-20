package com.ballastlane.pokemon.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.util.unit.DataSize
import org.springframework.validation.annotation.Validated

/**
 * Settings for updating local (proprietary) pokemon data (`app.local-data.*`).
 *
 * @property maxRequestSize largest accepted body of a PUT or PATCH on a pokemon; larger requests get 413
 */
@JvmRecord
@Validated
@ConfigurationProperties(prefix = "app.local-data")
final data class LocalDataProperties(val maxRequestSize: DataSize = DataSize.ofKilobytes(64))
