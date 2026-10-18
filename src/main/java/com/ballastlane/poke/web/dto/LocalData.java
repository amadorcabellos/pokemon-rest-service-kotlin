package com.ballastlane.poke.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * Proprietary data of a pokemon: owned by this service, never changed by the sync.
 *
 * @param modifiedAt when an update last changed it; {@code null} if it has never been changed
 * @param version increases with every update that changes something; also sent as the {@code ETag}
 */
public record LocalData(
		List<LocalizedName> localizedNames,
		List<GeoEntry> geoMetadata,
		List<String> tags,
		@Schema(description = "When an update last changed the local data; null if never") Instant modifiedAt,
		@Schema(description = "Version of the local data, also sent as the ETag", example = "3") long version) {

	public static final LocalData EMPTY = new LocalData(List.of(), List.of(), List.of(), null, 0);

}
