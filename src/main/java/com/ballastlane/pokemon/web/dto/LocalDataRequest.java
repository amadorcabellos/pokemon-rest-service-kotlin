package com.ballastlane.pokemon.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Body of a local data update. Only used to document the API: the body is parsed and validated by
 * {@code LocalDataRequestParser}, so that every problem can be reported with its field.
 */
@Schema(description = "Proprietary data of a pokemon. PUT needs all three fields (an empty list clears that kind "
		+ "of data). PATCH follows JSON Merge Patch: a missing field is kept, null clears it, a list replaces it.")
public record LocalDataRequest(
		@Schema(description = "At most 50, one per locale") List<LocalizedName> localizedNames,
		@Schema(description = "At most 20; the same kind and value may appear more than once")
		List<GeoEntry> geoMetadata,
		@Schema(description = "At most 30, unique; letters, digits, '-' and '_', stored in lowercase",
				example = "[\"mascot\", \"starter\"]") List<String> tags) {
}
