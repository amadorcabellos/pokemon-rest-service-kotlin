package com.ballastlane.poke.localdata;

/**
 * Limits on the proprietary data of one pokemon, enforced when it is updated through the API.
 */
public final class LocalDataLimits {

	public static final int MAX_LOCALIZED_NAMES = 50;

	public static final int MAX_GEO_ENTRIES = 20;

	public static final int MAX_TAGS = 30;

	/** Longest localized name, in characters. */
	public static final int MAX_LOCALIZED_NAME_LENGTH = 100;

	/** Longest locale in canonical form, matching the {@code pokemon_localized_name.locale} column. */
	public static final int MAX_LOCALE_LENGTH = 35;

	public static final int MAX_GEO_VALUE_LENGTH = 100;

	public static final int MAX_GEO_NOTE_LENGTH = 1000;

	public static final int MAX_TAG_LENGTH = 50;

	/** Deepest JSON nesting accepted in a request body (the valid shape needs 3). */
	public static final int MAX_JSON_DEPTH = 10;

	private LocalDataLimits() {
	}

}
