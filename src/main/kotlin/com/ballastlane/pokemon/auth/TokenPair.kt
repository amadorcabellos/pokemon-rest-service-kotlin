package com.ballastlane.pokemon.auth

import java.time.Instant

/**
 * What sign-in and refresh return. The refresh token is only ever shown here; the database keeps its hash.
 */
data class TokenPair(
    val accessToken: String,
    val accessTokenExpiresAt: Instant,
    val refreshToken: String,
    val refreshTokenExpiresAt: Instant,
) {

    // Never print the tokens themselves, even by accident.
    override fun toString(): String =
        "TokenPair[accessTokenExpiresAt=$accessTokenExpiresAt, refreshTokenExpiresAt=$refreshTokenExpiresAt]"
}
