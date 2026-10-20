package com.ballastlane.pokemon.web.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * A geographical fact about a pokemon.
 */
@JvmRecord
data class GeoEntry(
    @Schema(allowableValues = ["region", "area", "habitat"], example = "region") val kind: String,
    @Schema(example = "kanto") val value: String,
    @Schema(description = "Optional free-text note", example = "Common in Viridian Forest") val note: String?,
)
