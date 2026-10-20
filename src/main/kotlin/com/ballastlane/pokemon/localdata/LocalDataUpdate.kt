package com.ballastlane.pokemon.localdata

import com.ballastlane.pokemon.domain.GeoKind

/**
 * A validated and normalized change to the proprietary data of one pokemon.
 *
 * @property localizedNames the new localized names, or `null` to keep them as they are
 * @property geoMetadata the new geographical entries, or `null` to keep them as they are
 * @property tags the new tags, or `null` to keep them as they are
 */
data class LocalDataUpdate(
    val localizedNames: List<LocalizedNameValue>?,
    val geoMetadata: List<GeoEntryValue>?,
    val tags: List<String>?,
) {

    /**
     * @property locale canonical BCP 47 language tag, e.g. `ja-JP`
     */
    data class LocalizedNameValue(val locale: String, val name: String)

    /**
     * @property note optional; `null` when absent or blank
     */
    data class GeoEntryValue(val kind: GeoKind, val value: String, val note: String?)
}
