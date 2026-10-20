package com.ballastlane.pokemon.web.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * Body of refresh and sign-out: the refresh token is the only credential they need. Nullable so a missing token
 * reaches the service, which reports it as a field problem.
 */
data class RefreshTokenRequest(
    @Schema(description = "The refresh token from sign-in or the last refresh") val refreshToken: String?,
) {

    override fun toString(): String = "RefreshTokenRequest[refreshToken=<hidden>]"
}
