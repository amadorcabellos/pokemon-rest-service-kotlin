package com.ballastlane.pokemon.web

import com.ballastlane.pokemon.support.AbstractApiIntegrationTest
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.hasItem
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.options
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers

class CorsIntegrationTest : AbstractApiIntegrationTest() {

    @Test
    fun answersPreflightForAProtectedRouteWithoutAToken() {
        mockMvc.options("/api/v1/pokemon/pikachu") {
            header(HttpHeaders.ORIGIN, FRONTEND)
            header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PATCH")
            header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type,if-match")
        }.andExpect {
            status { isOk() }
            header { string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONTEND) }
            match(MockMvcResultMatchers.header().stringValues(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, hasItem(containsString("PATCH"))))
            header { doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS) }
        }
    }

    @Test
    fun exposesHeadersOnActualRequests() {
        mockMvc.post("/api/v1/admin/sync/runs") { header(HttpHeaders.ORIGIN, FRONTEND) }.andExpect {
            status { isUnauthorized() }
            header { string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONTEND) }
            header { string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, containsString(HttpHeaders.ETAG)) }
        }
    }

    @Test
    fun rejectsUnknownOrigins() {
        mockMvc.options("/api/v1/pokemon/pikachu") {
            header(HttpHeaders.ORIGIN, "https://evil.example")
            header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
        }.andExpect {
            status { isForbidden() }
            header { doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN) }
        }
    }

    private companion object {
        const val FRONTEND = "http://localhost:3000"
    }
}
