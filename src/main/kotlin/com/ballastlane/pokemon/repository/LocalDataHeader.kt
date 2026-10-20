package com.ballastlane.pokemon.repository

import java.time.Instant

/**
 * Identity and version of a pokemon's proprietary data, without loading the pokemon itself.
 *
 * @property pokemonId local id of the pokemon row
 * @property version version of its proprietary data (0 until the first update that changes something)
 * @property modifiedAt when an update last changed its proprietary data; `null` if never
 */
data class LocalDataHeader(val pokemonId: Long, val version: Long, val modifiedAt: Instant?)
