package com.ballastlane.pokemon.auth

import com.ballastlane.pokemon.config.AuthProperties
import com.ballastlane.pokemon.domain.AppUser
import com.ballastlane.pokemon.domain.AuthSession
import com.ballastlane.pokemon.domain.RefreshToken
import com.ballastlane.pokemon.repository.RefreshTokenRepository
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.HexFormat
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.stereotype.Component

/**
 * Issues the token pair of a session.
 * - Access token: a JWT signed with HS256, carrying the user (`sub`), the session (`sid`) and its token generation
 *   (`gen`).
 * - Refresh token: 256 random bits, base64url encoded. Only its SHA-256 hash is stored.
 *
 * Neither outlives the session's maximum lifetime.
 */
@Component
class TokenService(
    private val jwtEncoder: JwtEncoder,
    private val refreshTokenRepository: RefreshTokenRepository,
    properties: AuthProperties,
) {

    private val accessTokenTtl: Duration = properties.accessTokenTtl

    private val refreshTokenTtl: Duration = properties.refreshTokenTtl

    private val random = SecureRandom()

    /**
     * Issues tokens for the session's current generation and stores the refresh token's hash.
     */
    fun issue(session: AuthSession, user: AppUser, at: Instant): TokenPair {
        // JWT times are whole seconds; issuing on a whole second keeps the reported expiry exact.
        val now = at.truncatedTo(ChronoUnit.SECONDS)
        val accessExpiresAt = minOf(now.plus(accessTokenTtl), session.expiresAt)
        val claims = JwtClaimsSet.builder()
            .subject(user.id.toString())
            .issuedAt(now)
            .expiresAt(accessExpiresAt)
            .claim(SESSION_CLAIM, session.getId().toString())
            .claim(GENERATION_CLAIM, session.tokenGeneration)
            .claim(ROLES_CLAIM, user.roles.map { it.name }.sorted())
            .build()
        val accessToken = jwtEncoder
            .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
            .tokenValue

        val bytes = ByteArray(REFRESH_TOKEN_BYTES).also(random::nextBytes)
        val refreshToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val refreshExpiresAt = minOf(now.plus(refreshTokenTtl), session.expiresAt)
        refreshTokenRepository.save(
            RefreshToken(hash(refreshToken), session, session.tokenGeneration, now, refreshExpiresAt),
        )

        return TokenPair(accessToken, accessExpiresAt, refreshToken, refreshExpiresAt)
    }

    companion object {

        const val SESSION_CLAIM = "sid"

        const val GENERATION_CLAIM = "gen"

        const val ROLES_CLAIM = "roles"

        private const val REFRESH_TOKEN_BYTES = 32

        /**
         * SHA-256, hex encoded. A fast hash is enough: unlike a password, the token is 256 random bits.
         */
        fun hash(refreshToken: String): String =
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(refreshToken.toByteArray()))
    }
}
