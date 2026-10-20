package com.ballastlane.pokemon.auth

import com.ballastlane.pokemon.auth.AuthApi.Tokens
import com.ballastlane.pokemon.domain.Role
import com.ballastlane.pokemon.support.AbstractApiIntegrationTest
import io.kotest.inspectors.forAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldHaveLength
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.jvm.optionals.getOrNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.transaction.support.TransactionTemplate

/**
 * Registration, sign-in, refresh and sign-out end to end, against PostgreSQL.
 */
@ExtendWith(OutputCaptureExtension::class)
class AuthIntegrationTest @Autowired constructor(
    private val jdbc: JdbcTemplate,
    private val transactionTemplate: TransactionTemplate,
) : AbstractApiIntegrationTest() {

    private lateinit var auth: AuthApi

    @BeforeEach
    fun setUp() {
        auth = AuthApi(mockMvc)
    }

    @Test
    fun aRegisteredUserCanUpdateLocalDataButNotSynchronize() {
        auth.register(" Ash ", PASSWORD).andExpect {
            status { isCreated() }
            header { string("Location", "/api/v1/auth/user") }
            jsonPath("$.username") { value("ash") }
            jsonPath("$.roles[0]") { value("USER") }
            jsonPath("$.password") { doesNotExist() }
        }
        val tokens = auth.signIn("ASH", PASSWORD)
        mockMvc.get("/api/v1/pokemon/pikachu").andExpect { status { isOk() } }

        mockMvc.patch("/api/v1/pokemon/pikachu") {
            header(HttpHeaders.AUTHORIZATION, tokens.bearer())
            contentType = MERGE_PATCH
            content = """{ "tags": [ "mascot" ] }"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.tags[0]") { value("mascot") }
        }
        mockMvc.get("/api/v1/auth/user") { header(HttpHeaders.AUTHORIZATION, tokens.bearer()) }.andExpect {
            status { isOk() }
            jsonPath("$.username") { value("ash") }
        }
        mockMvc.post("/api/v1/admin/sync/runs") { header(HttpHeaders.AUTHORIZATION, tokens.bearer()) }
            .andExpect { status { isForbidden() } }
        mockMvc.post("/api/v1/admin/sync/runs").andExpect {
            status { isUnauthorized() }
            header { string("WWW-Authenticate", "Bearer") }
        }
    }

    @Test
    fun registrationRejectsDuplicatesAndInvalidInputAndNeverGrantsAdmin() {
        auth.register("ash", PASSWORD).andExpect { status { isCreated() } }
        auth.register("ASH", PASSWORD).andExpect { status { isConflict() } }
        auth.register("a", "short").andExpect {
            status { isBadRequest() }
            jsonPath("$.errors.length()") { value(2) }
        }
        mockMvc.post("/api/v1/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            content = """{ "username": "misty", "password": "$PASSWORD", "roles": ["ADMIN"] }"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.roles.length()") { value(1) }
        }

        userRepository.findByUsername("misty").getOrNull().shouldNotBeNull().roles.shouldContainExactly(Role.USER)
    }

    @Test
    fun concurrentRegistrationsOfOneUsernameGiveOneConflict() {
        val statuses = concurrently(2) { auth.register("brock", PASSWORD).andReturn().response.status }

        statuses.shouldContainExactlyInAnyOrder(201, 409)
    }

    @Test
    fun wrongUsernameAndWrongPasswordGetTheSameAnswer() {
        auth.register("ash", PASSWORD).andExpect { status { isCreated() } }

        val wrongPassword = auth.login("ash", "not-the-password").andExpect { status { isUnauthorized() } }
            .andReturn().response.contentAsString
        val unknownUser = auth.login("gary", PASSWORD).andExpect { status { isUnauthorized() } }
            .andReturn().response.contentAsString

        wrongPassword.replace("/api/v1/auth/login", "") shouldBe unknownUser.replace("/api/v1/auth/login", "")
    }

    @Test
    fun refreshEndsTheOldTokensAndReuseEndsTheSession() {
        auth.register("ash", PASSWORD)
        val first = auth.signIn("ash", PASSWORD)

        val second = auth.refreshed(first.refreshToken)

        second.refreshToken shouldNotBe first.refreshToken
        currentUserStatus(second) shouldBe 200
        currentUserStatus(first) shouldBe 401

        // The old refresh token again: treated as stolen, so the whole session ends.
        auth.refresh(first.refreshToken).andExpect {
            status { isUnauthorized() }
            jsonPath("$.title") { value("Invalid refresh token") }
        }
        currentUserStatus(second) shouldBe 401
        auth.refresh(second.refreshToken).andExpect { status { isUnauthorized() } }
    }

    @Test
    fun concurrentRefreshesWithOneTokenLetOnlyOneThrough() {
        auth.register("ash", PASSWORD)
        val tokens = auth.signIn("ash", PASSWORD)

        val statuses = concurrently(2) { auth.refresh(tokens.refreshToken).andReturn().response.status }

        statuses.shouldContainExactlyInAnyOrder(200, 401)
    }

    @Test
    fun signOutNeedsOnlyTheRefreshTokenAndEndsOnlyThatSession() {
        auth.register("ash", PASSWORD)
        val phone = auth.signIn("ash", PASSWORD)
        val laptop = auth.signIn("ash", PASSWORD)

        auth.logout(phone.refreshToken).andExpect { status { isNoContent() } }

        currentUserStatus(phone) shouldBe 401
        auth.refresh(phone.refreshToken).andExpect { status { isUnauthorized() } }
        currentUserStatus(laptop) shouldBe 200

        // Signing out again, or with anything else well-formed, says nothing about the token.
        auth.logout(phone.refreshToken).andExpect { status { isNoContent() } }
        auth.logout("not-a-real-token").andExpect { status { isNoContent() } }
        auth.logout(laptop.accessToken).andExpect { status { isNoContent() } }
        currentUserStatus(laptop) shouldBe 200
        mockMvc.post("/api/v1/auth/logout") {
            contentType = MediaType.APPLICATION_JSON
            content = "{}"
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.errors[0].field") { value("refreshToken") }
        }
    }

    @Test
    fun repeatedFailedSignInsAreThrottled() {
        auth.register("ash", PASSWORD)
        repeat(5) { auth.login("ash", "not-the-password").andExpect { status { isUnauthorized() } } }

        auth.login("ash", PASSWORD).andExpect {
            status { isTooManyRequests() }
            header { exists("Retry-After") }
            jsonPath("$.title") { value("Too many sign-in attempts") }
        }
        auth.register("misty", PASSWORD)
        auth.login("misty", PASSWORD).andExpect { status { isOk() } }
    }

    @Test
    fun deletedUsersAndRemovedRolesTakeEffectAtOnce() {
        val admin = bearerTokenFor("oak", Role.USER, Role.ADMIN)
        val user = bearerTokenFor("gary", Role.USER)
        mockMvc.get("/api/v1/admin/sync/runs/latest") { header(HttpHeaders.AUTHORIZATION, admin) }
            .andExpect { status { isNotFound() } }

        transactionTemplate.executeWithoutResult {
            userRepository.findByUsername("oak").getOrNull()?.removeRole(Role.ADMIN)
        }
        mockMvc.get("/api/v1/admin/sync/runs/latest") { header(HttpHeaders.AUTHORIZATION, admin) }
            .andExpect { status { isForbidden() } }

        userRepository.findByUsername("gary").getOrNull()?.id?.let(userRepository::deleteById)
        mockMvc.get("/api/v1/auth/user") { header(HttpHeaders.AUTHORIZATION, user) }
            .andExpect { status { isUnauthorized() } }
    }

    @Test
    fun neverStoresOrLogsPasswordsAndTokens(output: CapturedOutput) {
        auth.register("ash", PASSWORD)
        val first = auth.signIn("ash", PASSWORD)
        val second = auth.refreshed(first.refreshToken)
        auth.login("ash", "wrong-password-123")
        auth.logout(second.refreshToken)

        val hashes = jdbc.queryForList("SELECT token_hash FROM auth_refresh_token", String::class.java)
        hashes shouldHaveSize 2
        hashes shouldNotContain first.refreshToken
        hashes shouldNotContain second.refreshToken
        hashes.forAll { it shouldHaveLength 64 }
        val passwordHash = jdbc.queryForObject("SELECT password_hash FROM app_user", String::class.java).shouldNotBeNull()
        passwordHash shouldStartWith "{pbkdf2@SpringSecurity_v5_8}"
        passwordHash shouldNotContain PASSWORD
        // Only the application's log lines: MockMvc's own echo of the test's requests isn't a leak.
        val appLogs = output.all.lines().filter { it.contains("[pokemon-rest-service]") }.joinToString("\n")
        appLogs shouldContain "User ash signed in"
        listOf(
            PASSWORD, "wrong-password-123", first.accessToken, first.refreshToken, second.accessToken,
            second.refreshToken,
        ).forEach { appLogs shouldNotContain it }
    }

    private fun currentUserStatus(tokens: Tokens): Int =
        mockMvc.get("/api/v1/auth/user") { header(HttpHeaders.AUTHORIZATION, tokens.bearer()) }
            .andReturn().response.status

    private companion object {

        const val PASSWORD = "pikachu-i-choose-you"

        val MERGE_PATCH: MediaType = MediaType.parseMediaType("application/merge-patch+json")

        fun <T> concurrently(count: Int, call: (Int) -> T): List<T> {
            val start = CountDownLatch(1)
            Executors.newFixedThreadPool(count).use { executor ->
                val results = (0 until count).map { index ->
                    executor.submit(
                        Callable {
                            start.await()
                            call(index)
                        },
                    )
                }
                start.countDown()
                return results.map { it.get() }
            }
        }
    }
}
