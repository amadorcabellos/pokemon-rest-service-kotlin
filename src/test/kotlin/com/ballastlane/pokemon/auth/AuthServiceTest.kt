package com.ballastlane.pokemon.auth

import com.ballastlane.pokemon.config.AuthProperties
import com.ballastlane.pokemon.domain.AppUser
import com.ballastlane.pokemon.domain.AuthSession
import com.ballastlane.pokemon.domain.RefreshToken
import com.ballastlane.pokemon.domain.Role
import com.ballastlane.pokemon.domain.SessionEndReason
import com.ballastlane.pokemon.exception.InvalidCredentialsException
import com.ballastlane.pokemon.exception.InvalidRefreshTokenException
import com.ballastlane.pokemon.exception.InvalidRequestException
import com.ballastlane.pokemon.exception.TooManyLoginAttemptsException
import com.ballastlane.pokemon.exception.UsernameTakenException
import com.ballastlane.pokemon.repository.AppUserRepository
import com.ballastlane.pokemon.repository.AuthSessionRepository
import com.ballastlane.pokemon.repository.RefreshTokenRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Duration
import java.time.Instant
import java.util.Optional
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.crypto.password.PasswordEncoder

class AuthServiceTest {

    private val userRepository = mockk<AppUserRepository>()

    private val sessionRepository = mockk<AuthSessionRepository>()

    private val refreshTokenRepository = mockk<RefreshTokenRepository>()

    private val tokenService = mockk<TokenService>()

    private val limiter = mockk<LoginAttemptLimiter>(relaxUnitFun = true)

    private val clock = MutableClock(NOW)

    /** Fast stand-in for PBKDF2. */
    private val passwordEncoder = object : PasswordEncoder {

        override fun encode(raw: CharSequence?): String = "hashed:$raw"

        override fun matches(raw: CharSequence?, encoded: String?): Boolean = encoded == "hashed:$raw"
    }

    private lateinit var service: AuthService

    private lateinit var ash: AppUser

    @BeforeEach
    fun setUp() {
        val properties = AuthProperties(
            "0123456789abcdef0123456789abcdef", Duration.ofMinutes(15), Duration.ofDays(7), Duration.ofDays(30),
            AuthProperties.LoginAttempts(5, Duration.ofMinutes(15), Duration.ofMinutes(15)),
            AuthProperties.InitialAdmin(null, null),
        )
        service = AuthService(
            userRepository, sessionRepository, refreshTokenRepository, tokenService, passwordEncoder, limiter, clock,
            properties,
        )
        ash = AppUser("ash", "hashed:$PASSWORD", setOf(Role.USER))
        // What Mockito answered on its own: nobody stored, no failure locks a username.
        every { userRepository.findByUsername(any()) } returns Optional.empty()
        every { refreshTokenRepository.findForUpdate(any()) } returns Optional.empty()
        every { limiter.recordFailure(any()) } returns false
        every { userRepository.saveAndFlush(any()) } answers { firstArg() }
        every { sessionRepository.save(any()) } answers { firstArg() }
        every { tokenService.issue(any(), any(), any()) } returns TOKENS
    }

    @Test
    fun registersANormalizedUserWithTheUserRoleAndAHashedPassword() {
        val user = service.register("  Ash ", PASSWORD)

        user.username shouldBe "ash"
        user.roles.shouldContainExactly(Role.USER)
        user.passwordHash shouldBe "hashed:$PASSWORD"
    }

    @Test
    fun rejectsInvalidAndTakenUsernames() {
        shouldThrow<InvalidRequestException> { service.register("a", "short") }.problems shouldHaveSize 2

        every { userRepository.findByUsername("ash") } returns Optional.of(ash)
        shouldThrow<UsernameTakenException> { service.register("ASH", PASSWORD) }
    }

    @Test
    fun aConcurrentRegistrationOfTheSameUsernameIsAConflict() {
        every { userRepository.saveAndFlush(any()) } throws DataIntegrityViolationException("duplicate")

        shouldThrow<UsernameTakenException> { service.register("ash", PASSWORD) }
    }

    @Test
    fun signsInAndStartsASession() {
        every { userRepository.findByUsername("ash") } returns Optional.of(ash)

        service.login(" ASH ", PASSWORD) shouldBe TOKENS

        val session = slot<AuthSession>()
        verify(exactly = 1) { sessionRepository.save(capture(session)) }
        session.captured.expiresAt shouldBe NOW.plus(Duration.ofDays(30))
        verify(exactly = 1) { limiter.checkAllowed("ash") }
        verify(exactly = 1) { limiter.recordSuccess("ash") }
    }

    @Test
    fun wrongPasswordAndUnknownUsernameFailTheSameWay() {
        every { userRepository.findByUsername("ash") } returns Optional.of(ash)

        shouldThrow<InvalidCredentialsException> { service.login("ash", "wrong-password-123") }
            .message shouldBe "The username or password is incorrect"
        shouldThrow<InvalidCredentialsException> { service.login("misty", PASSWORD) }
            .message shouldBe "The username or password is incorrect"

        verify(exactly = 1) { limiter.recordFailure("ash") }
        verify(exactly = 1) { limiter.recordFailure("misty") }
        verify(exactly = 0) { sessionRepository.save(any()) }
    }

    @Test
    fun aLockedUsernameIsRejectedBeforeThePasswordIsChecked() {
        every { limiter.checkAllowed("ash") } throws TooManyLoginAttemptsException(Duration.ofMinutes(10))

        shouldThrow<TooManyLoginAttemptsException> { service.login("ash", PASSWORD) }
        verify(exactly = 0) { userRepository.findByUsername(any()) }
    }

    @Test
    fun signInNeedsBothFields() {
        shouldThrow<InvalidRequestException> { service.login(" ", null) }.problems shouldHaveSize 2
    }

    @Test
    fun refreshUsesUpTheTokenAndMovesToTheNextGeneration() {
        val session = AuthSession(ash, NOW, NOW.plus(Duration.ofDays(30)))
        val token = RefreshToken("hash", session, 0, NOW, NOW.plusSeconds(900))
        every { refreshTokenRepository.findForUpdate(TokenService.hash("refresh-1")) } returns Optional.of(token)
        clock.advance(Duration.ofMinutes(5))

        service.refresh("refresh-1") shouldBe TOKENS

        token.isUsed shouldBe true
        session.tokenGeneration shouldBe 1
        session.endedAt.shouldBeNull()
        verify(exactly = 1) { tokenService.issue(session, ash, NOW.plus(Duration.ofMinutes(5))) }
    }

    @Test
    fun reusingARefreshTokenEndsTheSession() {
        val session = AuthSession(ash, NOW, NOW.plus(Duration.ofDays(30)))
        val token = RefreshToken("hash", session, 0, NOW, NOW.plusSeconds(900))
        token.markUsed(NOW)
        every { refreshTokenRepository.findForUpdate(any()) } returns Optional.of(token)

        shouldThrow<InvalidRefreshTokenException> { service.refresh("refresh-1") }

        session.endReason shouldBe SessionEndReason.REUSE
        verify(exactly = 0) { tokenService.issue(any(), any(), any()) }
    }

    @Test
    fun rejectsUnknownExpiredAndEndedRefreshTokens() {
        shouldThrow<InvalidRefreshTokenException> { service.refresh("unknown") }

        val session = AuthSession(ash, NOW, NOW.plus(Duration.ofDays(30)))
        val expired = RefreshToken("expired", session, 0, NOW, NOW.plusSeconds(900))
        every { refreshTokenRepository.findForUpdate(TokenService.hash("expired")) } returns Optional.of(expired)
        clock.advance(Duration.ofMinutes(15))
        shouldThrow<InvalidRefreshTokenException> { service.refresh("expired") }

        val ended = AuthSession(ash, NOW, NOW.plus(Duration.ofDays(30)))
        ended.end(SessionEndReason.LOGOUT, NOW)
        val fresh = RefreshToken("fresh", ended, 0, clock.instant(), clock.instant().plusSeconds(900))
        every { refreshTokenRepository.findForUpdate(TokenService.hash("fresh")) } returns Optional.of(fresh)
        shouldThrow<InvalidRefreshTokenException> { service.refresh("fresh") }

        expired.isUsed shouldBe false
        verify(exactly = 0) { tokenService.issue(any(), any(), any()) }
    }

    @Test
    fun aSessionCantBeRefreshedPastItsMaximumLifetime() {
        val session = AuthSession(ash, NOW, NOW.plus(Duration.ofMinutes(10)))
        val token = RefreshToken("hash", session, 0, NOW, NOW.plus(Duration.ofMinutes(15)))
        every { refreshTokenRepository.findForUpdate(any()) } returns Optional.of(token)
        clock.advance(Duration.ofMinutes(11))

        shouldThrow<InvalidRefreshTokenException> { service.refresh("refresh-1") }
    }

    @Test
    fun logoutEndsTheSessionOfTheRefreshToken() {
        val session = AuthSession(ash, NOW, NOW.plus(Duration.ofDays(30)))
        every { refreshTokenRepository.findForUpdate(any()) } returns
            Optional.of(RefreshToken("hash", session, 0, NOW, NOW.plusSeconds(900)))

        service.logout("refresh-1")

        session.endReason shouldBe SessionEndReason.LOGOUT
        session.isActive(NOW) shouldBe false
    }

    @Test
    fun logoutAcceptsAnyWellFormedTokenButEndsTheSessionOnReuse() {
        service.logout("unknown")

        val session = AuthSession(ash, NOW, NOW.plus(Duration.ofDays(30)))
        val used = RefreshToken("hash", session, 0, NOW, NOW.plusSeconds(900))
        used.markUsed(NOW)
        every { refreshTokenRepository.findForUpdate(TokenService.hash("used")) } returns Optional.of(used)
        service.logout("used")
        session.endReason shouldBe SessionEndReason.REUSE

        shouldThrow<InvalidRequestException> { service.logout(" ") }
        shouldThrow<InvalidRequestException> { service.logout("x".repeat(4097)) }
    }

    private companion object {

        val NOW: Instant = Instant.parse("2026-10-18T10:00:00Z")

        const val PASSWORD = "pikachu-i-choose-you"

        val TOKENS = TokenPair("access", NOW.plusSeconds(900), "refresh", NOW.plusSeconds(900))
    }
}
