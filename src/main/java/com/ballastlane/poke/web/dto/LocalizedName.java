package com.ballastlane.poke.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The name of a pokemon in one locale.
 */
public record LocalizedName(
		@Schema(description = "BCP 47 language tag, stored in canonical form", example = "ja-JP") String locale,
		@Schema(example = "ピカチュウ") String name) {
}
