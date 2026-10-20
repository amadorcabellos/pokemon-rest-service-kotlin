package com.ballastlane.pokemon.web.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * Validated by `PasswordPolicy`, so every problem is reported together. Roles can't be requested. Both fields are
 * nullable so a missing one reaches the policy instead of failing JSON binding.
 */
@JvmRecord
data class RegisterRequest(
    @Schema(description = "3 to 50 characters: letters, digits, '.', '_' and '-'; case-insensitive", example = "ash")
    val username: String?,
    @Schema(
        description = "12 to 128 characters, any characters; not the username",
        example = "pikachu-i-choose-you",
        format = "password",
    )
    val password: String?,
) {

    override fun toString(): String = "RegisterRequest[username=$username]"
}
