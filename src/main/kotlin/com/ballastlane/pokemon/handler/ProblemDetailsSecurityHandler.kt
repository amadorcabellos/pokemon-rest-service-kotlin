package com.ballastlane.pokemon.handler

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.io.IOException
import java.net.URI
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.access.AccessDeniedHandler
import tools.jackson.databind.json.JsonMapper

/**
 * Answers 401 and 403 from the security filter chain as Problem Details, like every other error of the API.
 *
 * A 401 always carries an RFC 6750 `WWW-Authenticate: Bearer` challenge, with `error="invalid_token"` when a token
 * was sent but rejected. The reason is never detailed, and nothing from the token is echoed.
 */
class ProblemDetailsSecurityHandler(private val jsonMapper: JsonMapper) : AuthenticationEntryPoint, AccessDeniedHandler {

    @Throws(IOException::class)
    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        exception: AuthenticationException,
    ) {
        val tokenRejected = exception is OAuth2AuthenticationException
        log.debug(
            "401 for {} {}: {}", request.method, request.requestURI, if (tokenRejected) "token rejected" else "no token",
        )
        response.setHeader(
            HttpHeaders.WWW_AUTHENTICATE,
            if (tokenRejected) {
                "Bearer error=\"invalid_token\", error_description=\"The access token is invalid or has expired\""
            } else {
                "Bearer"
            },
        )
        write(
            request, response, HttpStatus.UNAUTHORIZED, "Authentication required",
            if (tokenRejected) {
                "The access token is invalid or has expired; refresh it or sign in again"
            } else {
                "This operation needs a bearer access token; sign in at /api/v1/auth/login"
            },
        )
    }

    @Throws(IOException::class)
    override fun handle(request: HttpServletRequest, response: HttpServletResponse, exception: AccessDeniedException) {
        log.debug("403 for {} {}", request.method, request.requestURI)
        write(
            request, response, HttpStatus.FORBIDDEN, "Forbidden",
            "Your account doesn't have the role this operation needs",
        )
    }

    private fun write(
        request: HttpServletRequest,
        response: HttpServletResponse,
        status: HttpStatus,
        title: String,
        detail: String,
    ) {
        val problem = ProblemDetail.forStatusAndDetail(status, detail)
        problem.title = title
        problem.instance = URI.create(request.requestURI)
        response.status = status.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        jsonMapper.writeValue(response.outputStream, problem)
    }

    private companion object {
        private val log = LoggerFactory.getLogger(ProblemDetailsSecurityHandler::class.java)
    }
}
