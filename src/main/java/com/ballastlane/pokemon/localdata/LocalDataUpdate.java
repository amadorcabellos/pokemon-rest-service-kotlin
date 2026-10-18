package com.ballastlane.pokemon.localdata;

import com.ballastlane.pokemon.domain.GeoKind;
import java.util.List;

/**
 * A validated and normalized change to the proprietary data of one pokemon.
 *
 * @param localizedNames the new localized names, or {@code null} to keep them as they are
 * @param geoMetadata the new geographical entries, or {@code null} to keep them as they are
 * @param tags the new tags, or {@code null} to keep them as they are
 */
public record LocalDataUpdate(
		List<LocalizedNameValue> localizedNames,
		List<GeoEntryValue> geoMetadata,
		List<String> tags) {

	/**
	 * @param locale canonical BCP 47 language tag, e.g. {@code ja-JP}
	 */
	public record LocalizedNameValue(String locale, String name) {
	}

	/**
	 * @param note optional; {@code null} when absent or blank
	 */
	public record GeoEntryValue(GeoKind kind, String value, String note) {
	}

}
