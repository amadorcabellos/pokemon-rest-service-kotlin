package com.ballastlane.pokemon.localdata

/**
 * Limits on the proprietary data of one pokemon, enforced when it is updated through the API.
 */
object LocalDataLimits {

    const val MAX_LOCALIZED_NAMES = 50

    const val MAX_GEO_ENTRIES = 20

    const val MAX_TAGS = 30

    /** Longest localized name, in characters. */
    const val MAX_LOCALIZED_NAME_LENGTH = 100

    /** Longest locale in canonical form, matching the `pokemon_localized_name.locale` column. */
    const val MAX_LOCALE_LENGTH = 35

    const val MAX_GEO_VALUE_LENGTH = 100

    const val MAX_GEO_NOTE_LENGTH = 1000

    const val MAX_TAG_LENGTH = 50

    /** Deepest JSON nesting accepted in a request body (the valid shape needs 3). */
    const val MAX_JSON_DEPTH = 10
}
