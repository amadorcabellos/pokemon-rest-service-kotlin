package com.ballastlane.poke.repository;

import java.time.Instant;

/**
 * Identity and version of a pokemon's proprietary data, without loading the pokemon itself.
 *
 * @param pokemonId local id of the pokemon row
 * @param version version of its proprietary data (0 until the first update that changes something)
 * @param modifiedAt when an update last changed its proprietary data; {@code null} if never
 */
public record LocalDataHeader(Long pokemonId, long version, Instant modifiedAt) {
}
