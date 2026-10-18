package com.ballastlane.pokemon.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A geographical fact about a pokemon.
 */
public record GeoEntry(
		@Schema(allowableValues = { "region", "area", "habitat" }, example = "region") String kind,
		@Schema(example = "kanto") String value,
		@Schema(description = "Optional free-text note", example = "Common in Viridian Forest") String note) {
}
