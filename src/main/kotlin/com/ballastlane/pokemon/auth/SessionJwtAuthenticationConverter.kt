package com.ballastlane.pokemon.auth

import com.ballastlane.pokemon.repository.AuthSessionRepository
import java.time.Clock
import java.util.UUID
import kotlin.jvm.optionals.getOrNull
import org.springframework.core.convert.converter.Converter
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.transaction.annotation.Transactional

/**
 * Turns a valid access token (signature and expiry already checked) into an authentication, after checking it
 * against the database: its session must still be active and at the token's generation, and its user must still
 * exist. Roles come from the database, not the token, so role changes take effect at once.
 *
 * Open (it is not a Spring stereotype, so the compiler plugin leaves it final) so `@Transactional` can proxy it.
 */
open class SessionJwtAuthenticationConverter(
    private val sessionRepository: AuthSessionRepository,
    private val clock: Clock,
) : Converter<Jwt, AbstractAuthenticationToken> {

    @Transactional(readOnly = true)
    override fun convert(jwt: Jwt): AbstractAuthenticationToken {
        val sessionId = sessionId(jwt)
        val generation = jwt.claims[TokenService.GENERATION_CLAIM]
        val session = sessionRepository.findWithUserById(sessionId).getOrNull()
            ?.takeIf { it.isActive(clock.instant()) }
            ?.takeIf { generation is Number && generation.toInt() == it.tokenGeneration }
            ?.takeIf { it.user.id.toString() == jwt.subject }
            ?: throw invalid()
        val authorities = session.user.roles.map { SimpleGrantedAuthority("ROLE_${it.name}") }
        return JwtAuthenticationToken(jwt, authorities, session.user.username)
    }

    private companion object {

        fun sessionId(jwt: Jwt): UUID = try {
            UUID.fromString(jwt.getClaimAsString(TokenService.SESSION_CLAIM) ?: throw invalid())
        } catch (ex: IllegalArgumentException) {
            throw invalid()
        }

        fun invalid() = InvalidBearerTokenException("The access token is no longer valid")
    }
}
