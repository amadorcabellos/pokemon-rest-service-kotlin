package com.ballastlane.pokemon.config

import com.ballastlane.pokemon.text.isJavaBlank
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/**
 * Settings for users, tokens and sessions (`app.security.*`).
 *
 * @property jwtSecret HMAC key that signs access tokens; at least 32 bytes. There is no default, so the application
 * refuses to start without one
 * @property accessTokenTtl how long an access token is valid
 * @property refreshTokenTtl how long a refresh token is valid; every refresh issues a new one
 * @property maxSessionLifetime how long a session can be kept alive by refreshing, counted from sign-in
 * @property loginAttempts limit on failed sign-ins per username
 * @property initialAdmin administrator created at startup when both fields are set and the user doesn't exist
 */
@Validated
@ConfigurationProperties(prefix = "app.security")
data class AuthProperties(
    @field:NotBlank val jwtSecret: String = "",
    val accessTokenTtl: Duration = Duration.ofMinutes(15),
    val refreshTokenTtl: Duration = Duration.ofDays(7),
    val maxSessionLifetime: Duration = Duration.ofDays(30),
    @field:Valid val loginAttempts: LoginAttempts = LoginAttempts(),
    val initialAdmin: InitialAdmin = InitialAdmin(),
) {

    init {
        require(jwtSecret.isJavaBlank() || jwtSecret.toByteArray().size >= MIN_SECRET_BYTES) {
            "app.security.jwt-secret (JWT_SECRET) must be at least $MIN_SECRET_BYTES bytes"
        }
    }

    /**
     * @property maxFailures failed sign-ins within `window` after which the username is locked
     * @property window how far back failures are counted
     * @property lockout how long a locked username gets 429
     */
    data class LoginAttempts(
        @field:Min(1) val maxFailures: Int = 5,
        val window: Duration = Duration.ofMinutes(15),
        val lockout: Duration = Duration.ofMinutes(15),
    )

    data class InitialAdmin(val username: String? = null, val password: String? = null) {

        val isConfigured: Boolean
            get() = username != null && !username.isJavaBlank() && !password.isNullOrEmpty()
    }

    companion object {

        /** HS256 needs a key of at least 256 bits. */
        const val MIN_SECRET_BYTES = 32
    }
}
