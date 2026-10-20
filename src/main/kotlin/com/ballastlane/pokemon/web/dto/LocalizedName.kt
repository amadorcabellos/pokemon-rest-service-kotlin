package com.ballastlane.pokemon.web.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * The name of a pokemon in one locale.
 */
@JvmRecord
data class LocalizedName(
    @Schema(description = "BCP 47 language tag, stored in canonical form", example = "ja-JP") val locale: String,
    @Schema(example = "ピカチュウ") val name: String,
)
