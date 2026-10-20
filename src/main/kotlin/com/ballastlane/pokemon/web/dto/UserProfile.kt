package com.ballastlane.pokemon.web.dto

import com.ballastlane.pokemon.domain.AppUser
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

/**
 * A user as the API shows it: never with the password.
 */
@JvmRecord
data class UserProfile(
    val id: Long?,
    @Schema(example = "ash") val username: String,
    @Schema(example = "[\"USER\"]") val roles: List<String>,
    val createdAt: Instant?,
) {

    companion object {

        fun from(user: AppUser): UserProfile =
            UserProfile(user.id, user.username, user.roles.map { it.name }.sorted(), user.createdAt)
    }
}
