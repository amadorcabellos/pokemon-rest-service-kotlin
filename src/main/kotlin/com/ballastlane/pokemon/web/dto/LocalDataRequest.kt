package com.ballastlane.pokemon.web.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * Body of a local data update. Only used to document the API: the body is parsed and validated by
 * `LocalDataRequestParser`, so that every problem can be reported with its field.
 */
@JvmRecord
@Schema(
    description = "Proprietary data of a pokemon. PUT needs all three fields (an empty list clears that kind of " +
        "data). PATCH follows JSON Merge Patch: a missing field is kept, null clears it, a list replaces it.",
)
data class LocalDataRequest(
    @Schema(description = "At most 50, one per locale") val localizedNames: List<LocalizedName>?,
    @Schema(description = "At most 20; the same kind and value may appear more than once")
    val geoMetadata: List<GeoEntry>?,
    @Schema(
        description = "At most 30, unique; letters, digits, '-' and '_', stored in lowercase",
        example = "[\"mascot\", \"starter\"]",
    )
    val tags: List<String>?,
)
