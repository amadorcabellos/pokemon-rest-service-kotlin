package com.ballastlane.pokemon.auth

import com.ballastlane.pokemon.config.AuthProperties
import com.ballastlane.pokemon.domain.AppUser
import com.ballastlane.pokemon.domain.AuthSession
import com.ballastlane.pokemon.domain.Role
import com.ballastlane.pokemon.domain.SessionEndReason
import com.ballastlane.pokemon.exception.InvalidCredentialsException
import com.ballastlane.pokemon.exception.InvalidRefreshTokenException
import com.ballastlane.pokemon.exception.InvalidRequestException
import com.ballastlane.pokemon.exception.TooManyLoginAttemptsException
import com.ballastlane.pokemon.exception.UsernameTakenException
import com.ballastlane.pokemon.localdata.FieldProblem
import com.ballastlane.pokemon.repository.AppUserRepository
import com.ballastlane.pokemon.repository.AuthSessionRepository
import com.ballastlane.pokemon.repository.RefreshTokenRepository
import com.ballastlane.pokemon.text.isJavaBlank
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.jvm.optionals.getOrNull
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Registration, sign-in, refresh and sign-out.
 *
 * Sign-in starts a session. Refreshing uses up the refresh token and moves the session to its next token generation,
 * which also retires the previous access token. A refresh token presented a second time is treated as stolen and
 * ends the session. Passwords and tokens are never logged.
 */
@Service
class AuthService(
    private val userRepository: AppUserRepository,
    private val sessionRepository: AuthSessionRepository,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val tokenService: TokenService,
    private val passwordEncoder: PasswordEncoder,
    private val loginAttemptLimiter: LoginAttemptLimiter,
    private val clock: Clock,
    properties: AuthProperties,
) {

    private val maxSessionLifetime: Duration = properties.maxSessionLifetime

    /** Checked against when the username doesn't exist, so a sign-in takes as long either way. */
    private val dummyPasswordHash: String = checkNotNull(passwordEncoder.encode("not-a-real-password-only-for-timing"))

    /**
     * Registers a user with the `USER` role. Roles are never taken from the request.
     *
     * @throws InvalidRequestException if the username or password breaks the policy
     * @throws UsernameTakenException if the username is taken (compared ignoring case)
     */
    @Transactional
    fun register(username: String?, password: String?): AppUser = createUser(username, password, setOf(Role.USER))

    /**
     * Creates a user with the given roles; for registration and the initial administrator.
     */
    @Transactional
    fun createUser(username: String?, password: String?, roles: Set<Role>): AppUser {
        val problems = PasswordPolicy.check(username, password)
        // The policy reports a missing username or password, so the null checks only tell the compiler.
        if (problems.isNotEmpty() || username == null || password == null) {
            throw InvalidRequestException(problems)
        }
        val normalized = PasswordPolicy.normalizeUsername(username)
        if (userRepository.findByUsername(normalized).isPresent) {
            throw UsernameTakenException(normalized)
        }
        try {
            val user = userRepository.saveAndFlush(AppUser(normalized, checkNotNull(passwordEncoder.encode(password)), roles))
            log.info("Registered user {} with roles {}", normalized, roles)
            return user
        } catch (ex: DataIntegrityViolationException) {
            // Registered concurrently by another request.
            throw UsernameTakenException(normalized)
        }
    }

    /**
     * @throws InvalidRequestException if a field is missing
     * @throws TooManyLoginAttemptsException if the username is locked after too many failures
     * @throws InvalidCredentialsException if the username or password is wrong (the same for both)
     */
    @Transactional(noRollbackFor = [InvalidCredentialsException::class])
    fun login(username: String?, password: String?): TokenPair {
        val problems = buildList {
            if (username == null || username.isJavaBlank()) {
                add(FieldProblem("username", "is required"))
            }
            if (password.isNullOrEmpty()) {
                add(FieldProblem("password", "is required"))
            }
        }
        if (problems.isNotEmpty() || username == null || password == null) {
            throw InvalidRequestException(problems)
        }

        val normalized = PasswordPolicy.normalizeUsername(username)
        loginAttemptLimiter.checkAllowed(normalized)
        val user = userRepository.findByUsername(normalized).getOrNull()
        val matches = passwordEncoder.matches(password, user?.passwordHash ?: dummyPasswordHash)
        if (user == null || !matches) {
            if (loginAttemptLimiter.recordFailure(normalized)) {
                log.warn("Too many failed sign-ins for {}; locked for a while", loggable(normalized))
            } else {
                log.info("Failed sign-in for {}", loggable(normalized))
            }
            throw InvalidCredentialsException()
        }

        loginAttemptLimiter.recordSuccess(normalized)
        val now = clock.instant()
        val session = sessionRepository.save(AuthSession(user, now, now.plus(maxSessionLifetime)))
        log.info("User {} signed in (session {})", normalized, session.getId())
        return tokenService.issue(session, user, now)
    }

    /**
     * Uses up the refresh token and issues a new pair. Ending a session on reuse is committed even though the call
     * fails, hence `noRollbackFor`.
     *
     * @throws InvalidRefreshTokenException if the token is unknown, expired, already used, or its session ended
     */
    @Transactional(noRollbackFor = [InvalidRefreshTokenException::class])
    fun refresh(refreshToken: String?): TokenPair {
        val presented = requireRefreshToken(refreshToken)
        val now = clock.instant()
        val token = refreshTokenRepository.findForUpdate(TokenService.hash(presented)).getOrNull()
            ?: throw InvalidRefreshTokenException()
        val session = token.session
        if (token.isUsed) {
            endForReuse(session, now)
            throw InvalidRefreshTokenException()
        }
        if (token.isExpired(now) || !session.isActive(now)) {
            throw InvalidRefreshTokenException()
        }

        token.markUsed(now)
        session.nextGeneration()
        val user = session.user
        log.info("Refreshed session {} of user {}", session.getId(), user.username)
        return tokenService.issue(session, user, now)
    }

    /**
     * Ends the session of the refresh token. Always succeeds for a well-formed request, so the response never says
     * whether the token was valid.
     *
     * @throws InvalidRequestException if the refresh token is missing or blank
     */
    @Transactional
    fun logout(refreshToken: String?) {
        val presented = requireRefreshToken(refreshToken)
        val now = clock.instant()
        val token = refreshTokenRepository.findForUpdate(TokenService.hash(presented)).getOrNull() ?: return
        val session = token.session
        if (token.isUsed) {
            endForReuse(session, now)
        } else if (session.isActive(now)) {
            session.end(SessionEndReason.LOGOUT, now)
            log.info("Session {} signed out", session.getId())
        }
    }

    @Transactional(readOnly = true)
    fun findUser(userId: Long): AppUser? = userRepository.findById(userId).getOrNull()

    private fun endForReuse(session: AuthSession, now: Instant) {
        if (session.endedAt == null) {
            session.end(SessionEndReason.REUSE, now)
            log.warn("A used refresh token was presented again; ended session {}", session.getId())
        }
    }

    private companion object {

        private val log = LoggerFactory.getLogger(AuthService::class.java)

        /** Generous enough for any token a client might send by mistake (sign-out answers 204 to those). */
        private const val MAX_TOKEN_LENGTH = 4096

        /**
         * The username as typed can be anything; only log it if it could be a real one (no line breaks or markup).
         */
        private fun loggable(username: String): String =
            if (PasswordPolicy.check(username, "x".repeat(PasswordPolicy.MIN_PASSWORD_LENGTH)).any { it.field == "username" }) {
                "<invalid username>"
            } else {
                username
            }

        private fun requireRefreshToken(refreshToken: String?): String {
            if (refreshToken == null || refreshToken.isJavaBlank()) {
                throw InvalidRequestException(listOf(FieldProblem("refreshToken", "is required")))
            }
            if (refreshToken.length > MAX_TOKEN_LENGTH) {
                throw InvalidRequestException(listOf(FieldProblem("refreshToken", "is too long")))
            }
            return refreshToken
        }
    }
}
