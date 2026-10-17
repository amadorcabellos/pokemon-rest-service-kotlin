package com.ballastlane.poke.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Pagination limits for the pokemon catalogue ({@code app.catalog.*}).
 */
@Validated
@ConfigurationProperties(prefix = "app.catalog")
public record CatalogProperties(
		@DefaultValue("20") @Min(1) int defaultPageSize,
		@DefaultValue("50") @Min(1) int maxPageSize) {
}
