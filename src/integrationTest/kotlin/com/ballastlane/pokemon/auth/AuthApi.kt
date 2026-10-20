package com.ballastlane.pokemon.auth

import com.jayway.jsonpath.JsonPath
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.post

/**
 * Calls the auth API the way a client would.
 */
internal class AuthApi(private val mockMvc: MockMvc) {

    fun register(username: String, password: String): ResultActionsDsl =
        postJson("/api/v1/auth/register", """{ "username": "$username", "password": "$password" }""")

    fun login(username: String, password: String): ResultActionsDsl =
        postJson("/api/v1/auth/login", """{ "username": "$username", "password": "$password" }""")

    fun refresh(refreshToken: String): ResultActionsDsl =
        postJson("/api/v1/auth/refresh", """{ "refreshToken": "$refreshToken" }""")

    fun logout(refreshToken: String): ResultActionsDsl =
        postJson("/api/v1/auth/logout", """{ "refreshToken": "$refreshToken" }""")

    fun signIn(username: String, password: String): Tokens =
        Tokens.from(login(username, password).andExpect { status { isOk() } })

    fun refreshed(refreshToken: String): Tokens = Tokens.from(refresh(refreshToken).andExpect { status { isOk() } })

    private fun postJson(path: String, body: String): ResultActionsDsl = mockMvc.post(path) {
        contentType = MediaType.APPLICATION_JSON
        content = body
    }

    data class Tokens(val accessToken: String, val refreshToken: String) {

        fun bearer(): String = "Bearer $accessToken"

        companion object {

            fun from(result: ResultActionsDsl): Tokens {
                val body = result.andReturn().response.contentAsString
                return Tokens(JsonPath.read(body, "$.accessToken"), JsonPath.read(body, "$.refreshToken"))
            }
        }
    }
}
