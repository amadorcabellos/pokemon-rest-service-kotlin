package com.ballastlane.pokemon.web

import com.ballastlane.pokemon.auth.AuthService
import com.ballastlane.pokemon.auth.TokenPair
import com.ballastlane.pokemon.domain.AppUser
import com.ballastlane.pokemon.domain.Role
import com.ballastlane.pokemon.exception.InvalidCredentialsException
import com.ballastlane.pokemon.exception.InvalidRefreshTokenException
import com.ballastlane.pokemon.exception.InvalidRequestException
import com.ballastlane.pokemon.exception.TooManyLoginAttemptsException
import com.ballastlane.pokemon.exception.UsernameTakenException
import com.ballastlane.pokemon.localdata.FieldProblem
import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.mockk.verify
import java.time.Duration
import java.time.Instant
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

@WebMvcTest(AuthController::class)
@Import(WebSecurityTestConfiguration::class)
class AuthControllerTest @Autowired constructor(private val mockMvc: MockMvc) {

    @MockkBean(relaxUnitFun = true)
    private lateinit var authService: AuthService

    @Test
    fun registersAUser() {
        every { authService.register("Ash", "pikachu-i-choose-you") } returns AppUser("ash", "hash", setOf(Role.USER))

        mockMvc.post("/api/v1/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            content = """{ "username": "Ash", "password": "pikachu-i-choose-you", "roles": ["ADMIN"] }"""
        }.andExpect {
            status { isCreated() }
            header { string("Location", "/api/v1/auth/user") }
            jsonPath("$.username") { value("ash") }
            jsonPath("$.roles[0]") { value("USER") }
            jsonPath("$.password") { doesNotExist() }
            jsonPath("$.passwordHash") { doesNotExist() }
        }
    }

    @Test
    fun registrationListsEveryProblemOrReportsAConflict() {
        every { authService.register("a", "short") } throws InvalidRequestException(
            listOf(
                FieldProblem("username", "must be 3 to 50 characters of letters, digits, '.', '_' and '-'"),
                FieldProblem("password", "must be 12 to 128 characters"),
            ),
        )
        every { authService.register("ash", "pikachu-i-choose-you") } throws UsernameTakenException("ash")

        mockMvc.post("/api/v1/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            content = """{ "username": "a", "password": "short" }"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.errors.length()") { value(2) }
            jsonPath("$.errors[1].field") { value("password") }
        }
        mockMvc.post("/api/v1/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            content = """{ "username": "ash", "password": "pikachu-i-choose-you" }"""
        }.andExpect {
            status { isConflict() }
            jsonPath("$.title") { value("Username already taken") }
        }
        mockMvc.post("/api/v1/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            content = "{"
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.title") { value("Malformed request body") }
        }
    }

    @Test
    fun registrationReportsMissingFieldsAsFieldProblems() {
        // The request fields are nullable, so a missing field reaches the policy instead of failing JSON binding.
        every { authService.register(null, null) } throws InvalidRequestException(
            listOf(FieldProblem("username", "is required"), FieldProblem("password", "is required")),
        )

        mockMvc.post("/api/v1/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            content = "{}"
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.title") { value("Invalid request") }
            jsonPath("$.errors[0].field") { value("username") }
            jsonPath("$.errors[1].field") { value("password") }
        }
    }

    @Test
    fun signsIn() {
        every { authService.login("ash", "pikachu-i-choose-you") } returns TOKENS

        mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{ "username": "ash", "password": "pikachu-i-choose-you" }"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.accessToken") { value("access-token") }
            jsonPath("$.tokenType") { value("Bearer") }
            jsonPath("$.expiresAt") { value("2026-10-18T10:15:00Z") }
            jsonPath("$.refreshToken") { value("refresh-token") }
            jsonPath("$.refreshExpiresAt") { value("2026-10-18T10:15:00Z") }
        }
    }

    @Test
    fun signInFailuresAre401And429() {
        every { authService.login("ash", "wrong-password-1") } throws InvalidCredentialsException()
        every { authService.login("misty", "wrong-password-1") } throws
            TooManyLoginAttemptsException(Duration.ofSeconds(90).plusMillis(1))

        mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{ "username": "ash", "password": "wrong-password-1" }"""
        }.andExpect {
            status { isUnauthorized() }
            header { doesNotExist("WWW-Authenticate") }
            jsonPath("$.title") { value("Invalid username or password") }
        }
        mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{ "username": "misty", "password": "wrong-password-1" }"""
        }.andExpect {
            status { isTooManyRequests() }
            header { string("Retry-After", "91") }
            jsonPath("$.title") { value("Too many sign-in attempts") }
        }
    }

    @Test
    fun refreshesWithoutAnAccessToken() {
        every { authService.refresh("refresh-1") } returns TOKENS
        every { authService.refresh("used") } throws InvalidRefreshTokenException()

        mockMvc.post("/api/v1/auth/refresh") {
            contentType = MediaType.APPLICATION_JSON
            header("Authorization", "Bearer expired-or-garbage")
            content = """{ "refreshToken": "refresh-1" }"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.refreshToken") { value("refresh-token") }
        }
        mockMvc.post("/api/v1/auth/refresh") {
            contentType = MediaType.APPLICATION_JSON
            content = """{ "refreshToken": "used" }"""
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.title") { value("Invalid refresh token") }
        }
    }

    @Test
    fun signsOutWithoutAnAccessToken() {
        mockMvc.post("/api/v1/auth/logout") {
            contentType = MediaType.APPLICATION_JSON
            content = """{ "refreshToken": "refresh-1" }"""
        }.andExpect {
            status { isNoContent() }
            content { string("") }
        }
        verify(exactly = 1) { authService.logout("refresh-1") }

        every { authService.logout(null) } throws
            InvalidRequestException(listOf(FieldProblem("refreshToken", "is required")))
        mockMvc.post("/api/v1/auth/logout") {
            contentType = MediaType.APPLICATION_JSON
            content = "{}"
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.errors[0].field") { value("refreshToken") }
        }
    }

    @Test
    fun showsTheSignedInUser() {
        every { authService.findUser(7L) } returns AppUser("ash", "hash", setOf(Role.USER))

        mockMvc.get("/api/v1/auth/user") {
            with(jwt().jwt { it.subject("7") })
        }.andExpect {
            status { isOk() }
            jsonPath("$.username") { value("ash") }
            jsonPath("$.passwordHash") { doesNotExist() }
        }
        mockMvc.get("/api/v1/auth/user").andExpect {
            status { isUnauthorized() }
            header { string("WWW-Authenticate", "Bearer") }
        }
    }

    private companion object {

        val EXPIRES: Instant = Instant.parse("2026-10-18T10:15:00Z")

        val TOKENS = TokenPair("access-token", EXPIRES, "refresh-token", EXPIRES)
    }
}
