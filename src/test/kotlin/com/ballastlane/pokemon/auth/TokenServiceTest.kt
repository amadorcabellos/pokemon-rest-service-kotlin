package com.ballastlane.pokemon.auth

import com.ballastlane.pokemon.config.AuthProperties
import com.ballastlane.pokemon.domain.AppUser
import com.ballastlane.pokemon.domain.AuthSession
import com.ballastlane.pokemon.domain.RefreshToken
import com.ballastlane.pokemon.domain.Role
import com.ballastlane.pokemon.repository.RefreshTokenRepository
import com.nimbusds.jose.jwk.source.ImmutableSecret
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldHaveLength
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Duration
import java.time.Instant
import javax.crypto.spec.SecretKeySpec
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder

class TokenServiceTest {

    private val repository = mockk<RefreshTokenRepository>()

    private val user = mockk<AppUser>()

    private lateinit var decoder: NimbusJwtDecoder

    @BeforeEach
    fun setUp() {
        every { user.id } returns 42L
        every { user.roles } returns setOf(Role.USER, Role.ADMIN)
        every { repository.save(any()) } answers { firstArg() }
        decoder = NimbusJwtDecoder.withSecretKey(key()).build()
        // Tokens are issued "now" in the test's past; only the signature and claims matter here.
        decoder.setJwtValidator { OAuth2TokenValidatorResult.success() }
    }

    @Test
    fun issuesTokensWithTheDefaultLifetimes() {
        val session = AuthSession(user, NOW, NOW.plus(Duration.ofDays(30)))

        val pair = service(DEFAULTS).issue(session, user, NOW)

        pair.accessTokenExpiresAt shouldBe NOW.plus(Duration.ofMinutes(15))
        pair.refreshTokenExpiresAt shouldBe NOW.plus(Duration.ofDays(7))
        val jwt = decoder.decode(pair.accessToken)
        jwt.subject shouldBe "42"
        jwt.getClaimAsString(TokenService.SESSION_CLAIM) shouldBe session.getId().toString()
        (jwt.claims[TokenService.GENERATION_CLAIM] as Number).toInt() shouldBe 0
        jwt.getClaimAsStringList(TokenService.ROLES_CLAIM).shouldContainExactly("ADMIN", "USER")
        jwt.issuedAt shouldBe NOW
    }

    @Test
    fun storesOnlyTheHashOfTheRefreshToken() {
        val session = AuthSession(user, NOW, NOW.plus(Duration.ofDays(30)))

        val pair = service(DEFAULTS).issue(session, user, NOW)

        val saved = slot<RefreshToken>()
        verify(exactly = 1) { repository.save(capture(saved)) }
        val storedId = saved.captured.getId()
        storedId shouldBe TokenService.hash(pair.refreshToken)
        storedId shouldHaveLength 64
        storedId shouldNotContain pair.refreshToken
        pair.refreshToken.length shouldBeGreaterThanOrEqual 43
        pair.toString() shouldNotContain pair.refreshToken
        pair.toString() shouldNotContain pair.accessToken
    }

    @Test
    fun followsTheConfiguredLifetimesButNeverOutlivesTheSession() {
        val session = AuthSession(user, NOW, NOW.plus(Duration.ofMinutes(10)))

        val capped = service(properties(Duration.ofMinutes(15), Duration.ofHours(1))).issue(session, user, NOW)
        val configured = service(properties(Duration.ofMinutes(2), Duration.ofMinutes(5)))
            .issue(AuthSession(user, NOW, NOW.plus(Duration.ofDays(1))), user, NOW)

        capped.accessTokenExpiresAt shouldBe NOW.plus(Duration.ofMinutes(10))
        capped.refreshTokenExpiresAt shouldBe NOW.plus(Duration.ofMinutes(10))
        configured.accessTokenExpiresAt shouldBe NOW.plus(Duration.ofMinutes(2))
        configured.refreshTokenExpiresAt shouldBe NOW.plus(Duration.ofMinutes(5))
    }

    @Test
    fun refreshTokensAreUnique() {
        val service = service(DEFAULTS)
        val session = AuthSession(user, NOW, NOW.plus(Duration.ofDays(30)))

        service.issue(session, user, NOW).refreshToken shouldNotBe service.issue(session, user, NOW).refreshToken
    }

    private fun service(properties: AuthProperties) =
        TokenService(NimbusJwtEncoder(ImmutableSecret(key())), repository, properties)

    private companion object {

        const val SECRET = "token-service-test-secret-0123456789"

        val NOW: Instant = Instant.parse("2026-10-18T10:00:00Z")

        /** The lifetimes `application.yml` and [AuthProperties] default to. */
        val DEFAULTS = properties(Duration.ofMinutes(15), Duration.ofDays(7))

        fun properties(accessTtl: Duration, refreshTtl: Duration) = AuthProperties(
            SECRET, accessTtl, refreshTtl, Duration.ofDays(30),
            AuthProperties.LoginAttempts(5, Duration.ofMinutes(15), Duration.ofMinutes(15)),
            AuthProperties.InitialAdmin(null, null),
        )

        fun key() = SecretKeySpec(SECRET.toByteArray(), "HmacSHA256")
    }
}
