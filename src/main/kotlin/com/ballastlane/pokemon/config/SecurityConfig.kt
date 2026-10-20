package com.ballastlane.pokemon.config

import com.ballastlane.pokemon.auth.SessionJwtAuthenticationConverter
import com.ballastlane.pokemon.handler.ProblemDetailsSecurityHandler
import com.ballastlane.pokemon.repository.AuthSessionRepository
import com.nimbusds.jose.jwk.source.ImmutableSecret
import java.time.Clock
import java.time.Duration
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.security.access.hierarchicalroles.RoleHierarchy
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.password.DelegatingPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtTimestampValidator
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.security.oauth2.server.resource.BearerTokenErrors
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher
import org.springframework.security.web.util.matcher.OrRequestMatcher
import org.springframework.security.web.util.matcher.RequestMatcher
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import tools.jackson.databind.json.JsonMapper

/**
 * Which routes need a bearer access token, and how tokens are checked.
 *
 * Protection is by method and path. Every route not listed here is public, and on public routes the `Authorization`
 * header isn't even read, so a bad token never breaks them. Stateless: no HTTP session, no cookies, no CSRF token.
 * Browsers on the origins in `app.cors.allowed-origins` may call the API; preflight requests are answered before any
 * authorization check.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        converter: SessionJwtAuthenticationConverter,
        problemDetails: ProblemDetailsSecurityHandler,
    ): SecurityFilterChain {
        // The Kotlin DSL can only swap the request cache, not remove it.
        http.requestCache { it.disable() }
        http {
            cors { }
            csrf { disable() }
            formLogin { disable() }
            httpBasic { disable() }
            logout { disable() }
            sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }
            authorizeHttpRequests {
                authorize(ADMIN_ROUTES, hasRole("ADMIN"))
                authorize(USER_ROUTES, hasRole("USER"))
                authorize(AUTHENTICATED_ROUTES, authenticated)
                authorize(anyRequest, permitAll)
            }
            oauth2ResourceServer {
                bearerTokenResolver = bearerTokenResolver()
                jwt { jwtAuthenticationConverter = converter }
                authenticationEntryPoint = problemDetails
                accessDeniedHandler = problemDetails
            }
            exceptionHandling {
                authenticationEntryPoint = problemDetails
                accessDeniedHandler = problemDetails
            }
        }
        return http.build()
    }

    /**
     * Tokens travel in the `Authorization` header, not cookies, so credentials stay off. Exposes the response headers
     * a client needs: `ETag` for `If-Match`, `Location` after creating something, and `Retry-After` on `429`/`503`.
     */
    @Bean
    fun corsConfigurationSource(properties: CorsProperties): CorsConfigurationSource {
        val cors = CorsConfiguration().apply {
            allowedOrigins = properties.allowedOrigins
            allowedMethods = listOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            allowedHeaders = listOf(
                HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE, HttpHeaders.ACCEPT, HttpHeaders.IF_MATCH,
                HttpHeaders.IF_NONE_MATCH,
            )
            exposedHeaders = listOf(HttpHeaders.ETAG, HttpHeaders.LOCATION, HttpHeaders.RETRY_AFTER)
            allowCredentials = false
            setMaxAge(properties.maxAge)
        }
        return UrlBasedCorsConfigurationSource().apply { registerCorsConfiguration("/**", cors) }
    }

    @Bean
    fun sessionJwtAuthenticationConverter(sessionRepository: AuthSessionRepository, clock: Clock) =
        SessionJwtAuthenticationConverter(sessionRepository, clock)

    @Bean
    fun problemDetailsSecurityHandler(jsonMapper: JsonMapper) = ProblemDetailsSecurityHandler(jsonMapper)

    @Bean
    fun jwtEncoder(properties: AuthProperties): JwtEncoder = NimbusJwtEncoder(ImmutableSecret(signingKey(properties)))

    @Bean
    fun jwtDecoder(properties: AuthProperties, clock: Clock): JwtDecoder {
        val decoder = NimbusJwtDecoder.withSecretKey(signingKey(properties))
            .macAlgorithm(MacAlgorithm.HS256)
            .build()
        val expiry = JwtTimestampValidator(CLOCK_SKEW).apply { setClock(clock) }
        decoder.setJwtValidator(DelegatingOAuth2TokenValidator(expiry))
        return decoder
    }

    /**
     * PBKDF2 rather than BCrypt: BCrypt only uses the first 72 bytes, and a 128-character Unicode password can be much
     * longer. Hashes are prefixed with their algorithm, so stronger ones can be added later.
     */
    @Bean
    fun passwordEncoder(): PasswordEncoder {
        val pbkdf2 = "pbkdf2@SpringSecurity_v5_8"
        return DelegatingPasswordEncoder(pbkdf2, mapOf(pbkdf2 to Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8()))
    }

    @Bean
    fun clock(): Clock = Clock.systemUTC()

    companion object {

        /** Synchronization: `ADMIN` only. */
        internal val ADMIN_ROUTES: RequestMatcher = OrRequestMatcher(
            route(HttpMethod.GET, "/api/v1/admin/sync/runs/*"),
            route(HttpMethod.POST, "/api/v1/admin/sync/runs"),
            route(HttpMethod.POST, "/api/v1/admin/sync/pokemon/*"),
        )

        /** Local data updates: `USER` (and so `ADMIN`). */
        internal val USER_ROUTES: RequestMatcher = OrRequestMatcher(
            route(HttpMethod.PUT, "/api/v1/pokemon/*"),
            route(HttpMethod.PATCH, "/api/v1/pokemon/*"),
        )

        /** Any signed-in user. */
        internal val AUTHENTICATED_ROUTES: RequestMatcher = route(HttpMethod.GET, "/api/v1/auth/user")

        internal val PROTECTED_ROUTES: RequestMatcher = OrRequestMatcher(ADMIN_ROUTES, USER_ROUTES, AUTHENTICATED_ROUTES)

        /** Tolerated difference between this server's clock and the one that issued a token. */
        private val CLOCK_SKEW: Duration = Duration.ofSeconds(30)

        /** `ADMIN` can do everything `USER` can. */
        @Bean
        @JvmStatic
        fun roleHierarchy(): RoleHierarchy = RoleHierarchyImpl.withDefaultRolePrefix().role("ADMIN").implies("USER").build()

        /**
         * Reads the bearer token only on protected routes; elsewhere (including refresh and logout) it's ignored. Two
         * `Authorization` headers are rejected rather than guessing which one counts.
         */
        internal fun bearerTokenResolver(): BearerTokenResolver {
            val delegate = DefaultBearerTokenResolver()
            return BearerTokenResolver { request ->
                when {
                    !PROTECTED_ROUTES.matches(request) -> null
                    request.getHeaders(HttpHeaders.AUTHORIZATION).toList().size > 1 ->
                        throw OAuth2AuthenticationException(BearerTokenErrors.invalidToken("Ambiguous credentials"))
                    else -> delegate.resolve(request)
                }
            }
        }

        private fun signingKey(properties: AuthProperties): SecretKey =
            SecretKeySpec(properties.jwtSecret.toByteArray(), "HmacSHA256")

        private fun route(method: HttpMethod, pattern: String): RequestMatcher =
            PathPatternRequestMatcher.withDefaults().matcher(method, pattern)
    }
}
