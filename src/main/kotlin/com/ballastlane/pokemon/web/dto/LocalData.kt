package com.ballastlane.pokemon.web.dto

import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

/**
 * Proprietary data of a pokemon: owned by this service, never changed by the sync.
 *
 * @property modifiedAt when an update last changed it; `null` if it has never been changed
 * @property version increases with every update that changes something; also sent as the `ETag`
 */
@JvmRecord
data class LocalData(
    val localizedNames: List<LocalizedName>,
    val geoMetadata: List<GeoEntry>,
    val tags: List<String>,
    @Schema(description = "When an update last changed the local data; null if never") val modifiedAt: Instant?,
    @Schema(description = "Version of the local data, also sent as the ETag", example = "3") val version: Long,
) {

    companion object {

        @JvmField
        val EMPTY = LocalData(listOf(), listOf(), listOf(), null, 0)
    }
}
