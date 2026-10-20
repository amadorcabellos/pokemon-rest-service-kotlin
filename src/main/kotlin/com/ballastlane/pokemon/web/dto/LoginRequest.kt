package com.ballastlane.pokemon.web.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * Both fields are nullable so a missing one reaches the service, which reports it as a field problem.
 */
@JvmRecord
data class LoginRequest(
    @Schema(example = "ash") val username: String?,
    @Schema(example = "pikachu-i-choose-you", format = "password") val password: String?,
) {

    override fun toString(): String = "LoginRequest[username=$username]"
}
