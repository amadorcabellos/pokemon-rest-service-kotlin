package com.ballastlane.pokemon.web.dto

import com.ballastlane.pokemon.auth.TokenPair
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

/**
 * Tokens from sign-in and refresh. Send `accessToken` as `Authorization: Bearer ...`; keep `refreshToken` to get the
 * next pair before it expires.
 */
@JvmRecord
data class TokenResponse(
    val accessToken: String,
    @Schema(example = "Bearer") val tokenType: String,
    val expiresAt: Instant,
    val refreshToken: String,
    val refreshExpiresAt: Instant,
) {

    override fun toString(): String = "TokenResponse[expiresAt=$expiresAt, refreshExpiresAt=$refreshExpiresAt]"

    companion object {

        fun from(pair: TokenPair): TokenResponse = TokenResponse(
            pair.accessToken, "Bearer", pair.accessTokenExpiresAt, pair.refreshToken, pair.refreshTokenExpiresAt,
        )
    }
}
