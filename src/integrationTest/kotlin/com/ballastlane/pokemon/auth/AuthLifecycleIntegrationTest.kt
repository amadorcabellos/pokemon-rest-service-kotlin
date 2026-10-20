package com.ballastlane.pokemon.auth

import com.ballastlane.pokemon.auth.AuthApi.Tokens
import com.ballastlane.pokemon.domain.SyncRunStatus
import com.ballastlane.pokemon.support.AbstractApiIntegrationTest
import com.ballastlane.pokemon.support.MutableClock
import com.ballastlane.pokemon.sync.PokemonSyncService
import com.jayway.jsonpath.JsonPath
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.time.Instant
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpHeaders
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

/**
 * Expiry (15-minute access tokens, 7-day refresh tokens, 30-day sessions: the defaults), the end of a sign-in
 * lockout, and the initial administrator, with a clock the tests move forward.
 */
@Import(AuthLifecycleIntegrationTest.ClockConfiguration::class)
@TestPropertySource(
    properties = [
        "app.security.initial-admin.username=professor-oak",
        "app.security.initial-admin.password=admin-password-123",
    ],
)
class AuthLifecycleIntegrationTest @Autowired constructor(
    private val clock: MutableClock,
    private val initialAdminCreator: InitialAdminCreator,
    private val syncService: PokemonSyncService,
) : AbstractApiIntegrationTest() {

    @TestConfiguration(proxyBeanMethods = false)
    class ClockConfiguration {

        @Bean
        @Primary
        fun testClock(): MutableClock = MutableClock(Instant.now())
    }

    private lateinit var auth: AuthApi

    @BeforeEach
    fun setUp() {
        clock.set(Instant.now())
        auth = AuthApi(mockMvc)
        auth.register("ash", PASSWORD)
    }

    @Test
    fun theAccessTokenExpiresAfterFifteenMinutesButCanStillBeRefreshed() {
        val tokens = auth.signIn("ash", PASSWORD)

        clock.advance(Duration.ofMinutes(14))
        currentUserStatus(tokens) shouldBe 200

        clock.advance(Duration.ofMinutes(2))
        currentUserStatus(tokens) shouldBe 401

        // 16 minutes in: the refresh token (7 days) still works.
        val refreshed = auth.refreshed(tokens.refreshToken)
        currentUserStatus(refreshed) shouldBe 200
    }

    @Test
    fun theRefreshTokenExpiresAfterSevenDays() {
        val tokens = auth.signIn("ash", PASSWORD)

        // Days after the access token expired, the refresh token still gets a new pair.
        clock.advance(Duration.ofDays(6))
        val refreshed = auth.refreshed(tokens.refreshToken)
        currentUserStatus(refreshed) shouldBe 200

        // The new refresh token has its own 7 days; without a refresh, the session is over after them.
        clock.advance(Duration.ofDays(7).plusMinutes(1))
        auth.refresh(refreshed.refreshToken).andExpect { status { isUnauthorized() } }
        currentUserStatus(refreshed) shouldBe 401
    }

    @Test
    fun refreshingKeepsASessionAliveUntilItsMaximumLifetime() {
        var tokens = auth.signIn("ash", PASSWORD)
        // Refreshing every 6 days, within the 7-day refresh token lifetime, until the 30-day session ends.
        for (days in 6 until 30 step 6) {
            clock.advance(Duration.ofDays(6))
            tokens = auth.refreshed(tokens.refreshToken)
            currentUserStatus(tokens) shouldBe 200
        }

        clock.advance(Duration.ofDays(6))
        currentUserStatus(tokens) shouldBe 401
        auth.refresh(tokens.refreshToken).andExpect { status { isUnauthorized() } }
    }

    @Test
    fun aLockedUsernameCanSignInAgainAfterTheLockout() {
        repeat(5) { auth.login("ash", "not-the-password") }
        auth.login("ash", PASSWORD).andExpect { status { isTooManyRequests() } }

        clock.advance(Duration.ofMinutes(15))
        auth.login("ash", PASSWORD).andExpect { status { isOk() } }
    }

    @Test
    fun theInitialAdministratorCanSynchronize() {
        // It was created at startup; the base class cleared users since, so run the startup step again.
        initialAdminCreator.run(DefaultApplicationArguments())
        val admin = auth.signIn("Professor-Oak", "admin-password-123")

        val run = mockMvc.post("/api/v1/admin/sync/runs") { header(HttpHeaders.AUTHORIZATION, admin.bearer()) }
            .andExpect { status { isAccepted() } }
            .andReturn().response.contentAsString
        val runId = JsonPath.read<Number>(run, "$.id").toLong()
        await().atMost(Duration.ofSeconds(20))
            .until({ syncService.getRun(runId).status }, { it != SyncRunStatus.RUNNING })

        // Running it again leaves the existing administrator alone.
        initialAdminCreator.run(DefaultApplicationArguments())
        userRepository.count() shouldBe 2
    }

    private fun currentUserStatus(tokens: Tokens): Int =
        mockMvc.get("/api/v1/auth/user") { header(HttpHeaders.AUTHORIZATION, tokens.bearer()) }
            .andReturn().response.status

    private companion object {
        const val PASSWORD = "pikachu-i-choose-you"
    }
}
