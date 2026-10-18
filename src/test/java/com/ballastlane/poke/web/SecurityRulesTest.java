package com.ballastlane.poke.web;

import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ballastlane.poke.auth.AuthService;
import com.ballastlane.poke.auth.TokenService;
import com.ballastlane.poke.config.CatalogProperties;
import com.ballastlane.poke.config.LocalDataConfig;
import com.ballastlane.poke.config.LocalDataProperties;
import com.ballastlane.poke.domain.AppUser;
import com.ballastlane.poke.domain.AuthSession;
import com.ballastlane.poke.domain.Role;
import com.ballastlane.poke.domain.SessionEndReason;
import com.ballastlane.poke.domain.SyncRunStatus;
import com.ballastlane.poke.domain.SyncTrigger;
import com.ballastlane.poke.localdata.LocalDataRequestParser;
import com.ballastlane.poke.localdata.LocalDataService;
import com.ballastlane.poke.repository.AuthSessionRepository;
import com.ballastlane.poke.service.PokemonCatalogService;
import com.ballastlane.poke.service.PokemonDetailsService;
import com.ballastlane.poke.sync.PokemonSyncService;
import com.ballastlane.poke.web.dto.EvolutionStage;
import com.ballastlane.poke.web.dto.LocalData;
import com.ballastlane.poke.web.dto.PokemonDetails;
import com.ballastlane.poke.web.dto.SyncRunReport;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Which routes need which token, checked with real signed tokens against the real security configuration.
 */
@WebMvcTest(controllers = { SyncController.class, PokemonController.class, AuthController.class })
@Import({ LocalDataRequestParser.class, LocalDataConfig.class, WebSecurityTestConfiguration.class })
class SecurityRulesTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JwtEncoder jwtEncoder;

	@MockitoBean
	private AuthSessionRepository sessionRepository;

	@MockitoBean
	private PokemonSyncService syncService;

	@MockitoBean
	private PokemonDetailsService detailsService;

	@MockitoBean
	private PokemonCatalogService catalogService;

	@MockitoBean
	private LocalDataService localDataService;

	@MockitoBean
	private AuthService authService;

	@TestConfiguration
	@EnableConfigurationProperties({ CatalogProperties.class, LocalDataProperties.class })
	static class PropertiesConfig {

	}

	private AuthSession userSession;

	private AuthSession adminSession;

	@BeforeEach
	void setUp() {
		userSession = session(1L, "ash", Set.of(Role.USER));
		adminSession = session(2L, "admin", Set.of(Role.USER, Role.ADMIN));
		given(syncService.startFullSync(SyncTrigger.API)).willReturn(new SyncRunReport(1L, SyncTrigger.API,
				SyncRunStatus.RUNNING, Instant.now(), null, 0, 0, 0, 0, List.of()));
		given(localDataService.update(any(), any(), any())).willReturn(LocalData.EMPTY);
		given(detailsService.getPokemon("pikachu")).willReturn(new PokemonDetails(25, "pikachu", null, List.of(), 0,
				null, new EvolutionStage(25, "pikachu", null, true, List.of())));
		given(localDataService.read(anyInt())).willReturn(LocalData.EMPTY);
	}

	static Stream<MockHttpServletRequestBuilder> protectedRoutes() {
		return Stream.of(
				get("/api/v1/admin/sync/runs/1"),
				get("/api/v1/admin/sync/runs/latest"),
				post("/api/v1/admin/sync/runs"),
				post("/api/v1/admin/sync/pokemon/pikachu"),
				request(HttpMethod.PATCH, "/api/v1/pokemon/pikachu").contentType(MediaType.APPLICATION_JSON)
						.content("{ \"tags\": [ \"invalid tag!\" ] }"),
				request(HttpMethod.PUT, "/api/v1/pokemon/pikachu").contentType(MediaType.APPLICATION_JSON)
						.content("not even json"),
				get("/api/v1/auth/user"));
	}

	@ParameterizedTest
	@MethodSource("protectedRoutes")
	void protectedRoutesNeedAToken(MockHttpServletRequestBuilder route) throws Exception {
		mockMvc.perform(route)
				.andExpect(status().isUnauthorized())
				.andExpect(header().string("WWW-Authenticate", "Bearer"))
				.andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Authentication required"));

		// Authentication comes first: invalid bodies never reach validation or a service.
		verifyNoInteractions(syncService, localDataService, authService);
	}

	@ParameterizedTest
	@MethodSource("protectedRoutes")
	void rejectsBadTokens(MockHttpServletRequestBuilder route) throws Exception {
		String otherSecret = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(
				"some-other-secret-0123456789abcdefgh".getBytes(StandardCharsets.UTF_8), "HmacSHA256")))
				.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
						claims(adminSession, Instant.now(), Instant.now().plusSeconds(900))))
				.getTokenValue();
		String expired = token(adminSession, Instant.now().minusSeconds(1200), Instant.now().minusSeconds(300));

		for (String token : List.of("garbage", otherSecret, expired)) {
			mockMvc.perform(route.header("Authorization", "Bearer " + token))
					.andExpect(status().isUnauthorized())
					.andExpect(header().string("WWW-Authenticate", startsWith("Bearer error=\"invalid_token\"")))
					.andExpect(jsonPath("$.detail").value(
							"The access token is invalid or has expired; refresh it or sign in again"));
		}
		verifyNoInteractions(syncService, localDataService, authService);
	}

	@Test
	void adminRoutesNeedTheAdminRole() throws Exception {
		mockMvc.perform(post("/api/v1/admin/sync/runs").header("Authorization", bearer(userSession)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.title").value("Forbidden"));
		mockMvc.perform(post("/api/v1/admin/sync/runs").header("Authorization", bearer(adminSession)))
				.andExpect(status().isAccepted());
	}

	@Test
	void localDataUpdatesNeedTheUserRole() throws Exception {
		mockMvc.perform(request(HttpMethod.PATCH, "/api/v1/pokemon/pikachu").header("Authorization",
						bearer(userSession)).contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isOk());
		// ADMIN implies USER.
		mockMvc.perform(request(HttpMethod.PUT, "/api/v1/pokemon/pikachu").header("Authorization",
						bearer(adminSession)).contentType(MediaType.APPLICATION_JSON)
						.content("{ \"localizedNames\": [], \"geoMetadata\": [], \"tags\": [] }"))
				.andExpect(status().isOk());
	}

	@Test
	void endedSessionsAndOlderGenerationsAreRejected() throws Exception {
		String retired = bearer(userSession);
		userSession.nextGeneration();
		mockMvc.perform(request(HttpMethod.PATCH, "/api/v1/pokemon/pikachu").header("Authorization", retired)
						.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isUnauthorized());

		String current = bearer(userSession);
		userSession.end(SessionEndReason.LOGOUT, Instant.now());
		mockMvc.perform(request(HttpMethod.PATCH, "/api/v1/pokemon/pikachu").header("Authorization", current)
						.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isUnauthorized());

		given(sessionRepository.findWithUserById(adminSession.getId())).willReturn(Optional.empty());
		mockMvc.perform(post("/api/v1/admin/sync/runs").header("Authorization", bearer(adminSession)))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void rolesComeFromTheDatabaseNotTheToken() throws Exception {
		// The token claims ADMIN, but the stored user only has USER now.
		String token = bearer(adminSession);
		AppUser demoted = mock(AppUser.class);
		given(demoted.getId()).willReturn(2L);
		given(demoted.getUsername()).willReturn("admin");
		given(demoted.getRoles()).willReturn(Set.of(Role.USER));
		AuthSession current = new AuthSession(demoted, Instant.now(), Instant.now().plus(Duration.ofDays(30)));
		given(sessionRepository.findWithUserById(adminSession.getId())).willReturn(Optional.of(current));

		mockMvc.perform(post("/api/v1/admin/sync/runs").header("Authorization", token))
				.andExpect(status().isForbidden());
	}

	@Test
	void twoAuthorizationHeadersAreRejected() throws Exception {
		mockMvc.perform(post("/api/v1/admin/sync/runs").header("Authorization", bearer(adminSession))
						.header("Authorization", bearer(adminSession)))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void publicRoutesIgnoreTokensEvenBadOnes() throws Exception {
		given(authService.register(any(), any())).willReturn(new AppUser("ash", "hash", Set.of(Role.USER)));
		mockMvc.perform(get("/api/v1/pokemon/pikachu")).andExpect(status().isOk());
		mockMvc.perform(get("/api/v1/pokemon/pikachu").header("Authorization", "Bearer garbage"))
				.andExpect(status().isOk());
		mockMvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer garbage")
						.contentType(MediaType.APPLICATION_JSON).content("{ \"refreshToken\": \"r\" }"))
				.andExpect(status().isNoContent());
		mockMvc.perform(post("/api/v1/auth/register").header("Authorization", "Bearer garbage")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{ \"username\": \"ash\", \"password\": \"pikachu-i-choose-you\" }"))
				.andExpect(status().isCreated());
	}

	@Test
	void unsupportedMethodsStill405() throws Exception {
		mockMvc.perform(delete("/api/v1/pokemon/pikachu")).andExpect(status().isMethodNotAllowed());
		mockMvc.perform(delete("/api/v1/pokemon/pikachu").header("Authorization", bearer(userSession)))
				.andExpect(status().isMethodNotAllowed());
	}

	@Test
	void noSessionCookieIsEverSet() throws Exception {
		mockMvc.perform(post("/api/v1/admin/sync/runs").header("Authorization", bearer(adminSession)))
				.andExpect(header().doesNotExist("Set-Cookie"));
	}

	private AuthSession session(long userId, String username, Set<Role> roles) {
		AppUser user = mock(AppUser.class);
		given(user.getId()).willReturn(userId);
		given(user.getUsername()).willReturn(username);
		given(user.getRoles()).willReturn(roles);
		AuthSession session = new AuthSession(user, Instant.now(), Instant.now().plus(Duration.ofDays(30)));
		given(sessionRepository.findWithUserById(session.getId())).willReturn(Optional.of(session));
		return session;
	}

	private String bearer(AuthSession session) {
		return "Bearer " + token(session, Instant.now(), Instant.now().plusSeconds(900));
	}

	private String token(AuthSession session, Instant issuedAt, Instant expiresAt) {
		return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
				claims(session, issuedAt, expiresAt))).getTokenValue();
	}

	private static JwtClaimsSet claims(AuthSession session, Instant issuedAt, Instant expiresAt) {
		return JwtClaimsSet.builder()
				.subject(session.getUser().getId().toString())
				.issuedAt(issuedAt)
				.expiresAt(expiresAt)
				.claim(TokenService.SESSION_CLAIM, session.getId().toString())
				.claim(TokenService.GENERATION_CLAIM, session.getTokenGeneration())
				.claim(TokenService.ROLES_CLAIM, session.getUser().getRoles().stream().map(Role::name).toList())
				.build();
	}

}
