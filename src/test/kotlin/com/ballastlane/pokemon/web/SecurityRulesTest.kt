package com.ballastlane.pokemon.web

import com.ballastlane.pokemon.auth.AuthService
import com.ballastlane.pokemon.auth.TokenService
import com.ballastlane.pokemon.config.CatalogProperties
import com.ballastlane.pokemon.config.LocalDataConfig
import com.ballastlane.pokemon.config.LocalDataProperties
import com.ballastlane.pokemon.domain.AppUser
import com.ballastlane.pokemon.domain.AuthSession
import com.ballastlane.pokemon.domain.Role
import com.ballastlane.pokemon.domain.SessionEndReason
import com.ballastlane.pokemon.domain.SyncRunStatus
import com.ballastlane.pokemon.domain.SyncTrigger
import com.ballastlane.pokemon.localdata.LocalDataRequestParser
import com.ballastlane.pokemon.localdata.LocalDataService
import com.ballastlane.pokemon.repository.AuthSessionRepository
import com.ballastlane.pokemon.service.PokemonCatalogService
import com.ballastlane.pokemon.service.PokemonDetailsService
import com.ballastlane.pokemon.sync.PokemonSyncService
import com.ballastlane.pokemon.web.dto.EvolutionStage
import com.ballastlane.pokemon.web.dto.LocalData
import com.ballastlane.pokemon.web.dto.PokemonDetails
import com.ballastlane.pokemon.web.dto.SyncRunReport
import com.ninjasquad.springmockk.MockkBean
import com.nimbusds.jose.jwk.source.ImmutableSecret
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Duration
import java.time.Instant
import java.util.Optional
import java.util.stream.Stream
import javax.crypto.spec.SecretKeySpec
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Which routes need which token, checked with real signed tokens against the real security configuration.
 */
@WebMvcTest(controllers = [SyncController::class, PokemonController::class, AuthController::class])
@Import(LocalDataRequestParser::class, LocalDataConfig::class, WebSecurityTestConfiguration::class)
class SecurityRulesTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val jwtEncoder: JwtEncoder,
) {

    @MockkBean
    private lateinit var sessionRepository: AuthSessionRepository

    @MockkBean
    private lateinit var syncService: PokemonSyncService

    @MockkBean
    private lateinit var detailsService: PokemonDetailsService

    @MockkBean
    private lateinit var catalogService: PokemonCatalogService

    @MockkBean
    private lateinit var localDataService: LocalDataService

    @MockkBean(relaxUnitFun = true)
    private lateinit var authService: AuthService

    @TestConfiguration
    @EnableConfigurationProperties(CatalogProperties::class, LocalDataProperties::class)
    class PropertiesConfig

    private lateinit var userSession: AuthSession

    private lateinit var adminSession: AuthSession

    @BeforeEach
    fun setUp() {
        every { sessionRepository.findWithUserById(any()) } returns Optional.empty()
        userSession = session(1L, "ash", setOf(Role.USER))
        adminSession = session(2L, "admin", setOf(Role.USER, Role.ADMIN))
        every { syncService.startFullSync(SyncTrigger.API) } returns
            SyncRunReport(1L, SyncTrigger.API, SyncRunStatus.RUNNING, Instant.now(), null, 0, 0, 0, 0, listOf())
        every { localDataService.update(any(), any(), any()) } returns LocalData.EMPTY
        every { detailsService.getPokemon("pikachu") } returns PokemonDetails(
            25, "pikachu", null, listOf(), 0, null, EvolutionStage(25, "pikachu", null, true, listOf()),
        )
        every { localDataService.read(any()) } returns LocalData.EMPTY
    }

    @ParameterizedTest
    @MethodSource("protectedRoutes")
    fun protectedRoutesNeedAToken(route: MockHttpServletRequestBuilder) {
        mockMvc.perform(route).andExpectAll(
            status().isUnauthorized,
            header().string("WWW-Authenticate", "Bearer"),
            content().contentType(MediaType.APPLICATION_PROBLEM_JSON),
            jsonPath("$.title").value("Authentication required"),
        )

        // Authentication comes first: invalid bodies never reach validation or a service.
        verify { listOf(syncService, localDataService, authService) wasNot Called }
    }

    @ParameterizedTest
    @MethodSource("protectedRoutes")
    fun rejectsBadTokens(route: MockHttpServletRequestBuilder) {
        val otherSecret = NimbusJwtEncoder(
            ImmutableSecret(SecretKeySpec("some-other-secret-0123456789abcdefgh".toByteArray(), "HmacSHA256")),
        ).encode(
            JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(),
                claims(adminSession, Instant.now(), Instant.now().plusSeconds(900)),
            ),
        ).tokenValue
        val expired = token(adminSession, Instant.now().minusSeconds(1200), Instant.now().minusSeconds(300))

        for (token in listOf("garbage", otherSecret, expired)) {
            mockMvc.perform(route.header("Authorization", "Bearer $token")).andExpectAll(
                status().isUnauthorized,
                header().string("WWW-Authenticate", startsWith("Bearer error=\"invalid_token\"")),
                jsonPath("$.detail").value("The access token is invalid or has expired; refresh it or sign in again"),
            )
        }
        verify { listOf(syncService, localDataService, authService) wasNot Called }
    }

    @Test
    fun adminRoutesNeedTheAdminRole() {
        mockMvc.post("/api/v1/admin/sync/runs") { header("Authorization", bearer(userSession)) }.andExpect {
            status { isForbidden() }
            jsonPath("$.title") { value("Forbidden") }
        }
        mockMvc.post("/api/v1/admin/sync/runs") { header("Authorization", bearer(adminSession)) }
            .andExpect { status { isAccepted() } }
    }

    @Test
    fun localDataUpdatesNeedTheUserRole() {
        mockMvc.request(HttpMethod.PATCH, "/api/v1/pokemon/pikachu") {
            header("Authorization", bearer(userSession))
            contentType = MediaType.APPLICATION_JSON
            content = "{}"
        }.andExpect { status { isOk() } }
        // ADMIN implies USER.
        mockMvc.request(HttpMethod.PUT, "/api/v1/pokemon/pikachu") {
            header("Authorization", bearer(adminSession))
            contentType = MediaType.APPLICATION_JSON
            content = """{ "localizedNames": [], "geoMetadata": [], "tags": [] }"""
        }.andExpect { status { isOk() } }
    }

    @Test
    fun endedSessionsAndOlderGenerationsAreRejected() {
        val retired = bearer(userSession)
        userSession.nextGeneration()
        mockMvc.request(HttpMethod.PATCH, "/api/v1/pokemon/pikachu") {
            header("Authorization", retired)
            contentType = MediaType.APPLICATION_JSON
            content = "{}"
        }.andExpect { status { isUnauthorized() } }

        val current = bearer(userSession)
        userSession.end(SessionEndReason.LOGOUT, Instant.now())
        mockMvc.request(HttpMethod.PATCH, "/api/v1/pokemon/pikachu") {
            header("Authorization", current)
            contentType = MediaType.APPLICATION_JSON
            content = "{}"
        }.andExpect { status { isUnauthorized() } }

        every { sessionRepository.findWithUserById(adminSession.getId()) } returns Optional.empty()
        mockMvc.post("/api/v1/admin/sync/runs") { header("Authorization", bearer(adminSession)) }
            .andExpect { status { isUnauthorized() } }
    }

    @Test
    fun rolesComeFromTheDatabaseNotTheToken() {
        // The token claims ADMIN, but the stored user only has USER now.
        val token = bearer(adminSession)
        val demoted = mockk<AppUser>()
        every { demoted.id } returns 2L
        every { demoted.username } returns "admin"
        every { demoted.roles } returns setOf(Role.USER)
        val current = AuthSession(demoted, Instant.now(), Instant.now().plus(Duration.ofDays(30)))
        every { sessionRepository.findWithUserById(adminSession.getId()) } returns Optional.of(current)

        mockMvc.post("/api/v1/admin/sync/runs") { header("Authorization", token) }.andExpect { status { isForbidden() } }
    }

    @Test
    fun twoAuthorizationHeadersAreRejected() {
        mockMvc.post("/api/v1/admin/sync/runs") {
            header("Authorization", bearer(adminSession))
            header("Authorization", bearer(adminSession))
        }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun publicRoutesIgnoreTokensEvenBadOnes() {
        every { authService.register(any(), any()) } returns AppUser("ash", "hash", setOf(Role.USER))
        mockMvc.get("/api/v1/pokemon/pikachu").andExpect { status { isOk() } }
        mockMvc.get("/api/v1/pokemon/pikachu") { header("Authorization", "Bearer garbage") }
            .andExpect { status { isOk() } }
        mockMvc.post("/api/v1/auth/logout") {
            header("Authorization", "Bearer garbage")
            contentType = MediaType.APPLICATION_JSON
            content = """{ "refreshToken": "r" }"""
        }.andExpect { status { isNoContent() } }
        mockMvc.post("/api/v1/auth/register") {
            header("Authorization", "Bearer garbage")
            contentType = MediaType.APPLICATION_JSON
            content = """{ "username": "ash", "password": "pikachu-i-choose-you" }"""
        }.andExpect { status { isCreated() } }
    }

    @Test
    fun unsupportedMethodsStill405() {
        mockMvc.delete("/api/v1/pokemon/pikachu").andExpect { status { isMethodNotAllowed() } }
        mockMvc.delete("/api/v1/pokemon/pikachu") { header("Authorization", bearer(userSession)) }
            .andExpect { status { isMethodNotAllowed() } }
    }

    @Test
    fun noSessionCookieIsEverSet() {
        mockMvc.post("/api/v1/admin/sync/runs") { header("Authorization", bearer(adminSession)) }
            .andExpect { header { doesNotExist("Set-Cookie") } }
    }

    private fun session(userId: Long, username: String, roles: Set<Role>): AuthSession {
        val user = mockk<AppUser>()
        every { user.id } returns userId
        every { user.username } returns username
        every { user.roles } returns roles
        val session = AuthSession(user, Instant.now(), Instant.now().plus(Duration.ofDays(30)))
        every { sessionRepository.findWithUserById(session.getId()) } returns Optional.of(session)
        return session
    }

    private fun bearer(session: AuthSession): String =
        "Bearer " + token(session, Instant.now(), Instant.now().plusSeconds(900))

    private fun token(session: AuthSession, issuedAt: Instant, expiresAt: Instant): String = jwtEncoder.encode(
        JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims(session, issuedAt, expiresAt)),
    ).tokenValue

    companion object {

        @JvmStatic
        fun protectedRoutes(): Stream<MockHttpServletRequestBuilder> = Stream.of(
            MockMvcRequestBuilders.get("/api/v1/admin/sync/runs/1"),
            MockMvcRequestBuilders.get("/api/v1/admin/sync/runs/latest"),
            MockMvcRequestBuilders.post("/api/v1/admin/sync/runs"),
            MockMvcRequestBuilders.post("/api/v1/admin/sync/pokemon/pikachu"),
            MockMvcRequestBuilders.request(HttpMethod.PATCH, "/api/v1/pokemon/pikachu")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{ "tags": [ "invalid tag!" ] }"""),
            MockMvcRequestBuilders.request(HttpMethod.PUT, "/api/v1/pokemon/pikachu")
                .contentType(MediaType.APPLICATION_JSON)
                .content("not even json"),
            MockMvcRequestBuilders.get("/api/v1/auth/user"),
        )

        private fun claims(session: AuthSession, issuedAt: Instant, expiresAt: Instant): JwtClaimsSet =
            JwtClaimsSet.builder()
                .subject(session.user.id.toString())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim(TokenService.SESSION_CLAIM, session.getId().toString())
                .claim(TokenService.GENERATION_CLAIM, session.tokenGeneration)
                .claim(TokenService.ROLES_CLAIM, session.user.roles.map { it.name })
                .build()
    }
}
