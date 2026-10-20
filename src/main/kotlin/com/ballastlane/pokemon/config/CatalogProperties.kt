package com.ballastlane.pokemon.config

import jakarta.validation.constraints.Min
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/**
 * Pagination limits for the pokemon catalogue (`app.catalog.*`).
 */
@Validated
@ConfigurationProperties(prefix = "app.catalog")
data class CatalogProperties(
    @field:Min(1) val defaultPageSize: Int = 20,
    @field:Min(1) val maxPageSize: Int = 50,
)
