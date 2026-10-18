package com.ballastlane.poke.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ballastlane.poke.auth.AuthApi.Tokens;
import com.ballastlane.poke.domain.SyncRunStatus;
import com.ballastlane.poke.support.AbstractApiIntegrationTest;
import com.ballastlane.poke.support.MutableClock;
import com.ballastlane.poke.sync.PokemonSyncService;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;

/**
 * Expiry (15-minute access tokens, 7-day refresh tokens, 30-day sessions: the defaults), the end of a sign-in
 * lockout, and the initial administrator, with a clock the tests move forward.
 */
@Import(AuthLifecycleIntegrationTest.ClockConfiguration.class)
@TestPropertySource(properties = {
		"app.security.initial-admin.username=professor-oak",
		"app.security.initial-admin.password=admin-password-123" })
class AuthLifecycleIntegrationTest extends AbstractApiIntegrationTest {

	private static final String PASSWORD = "pikachu-i-choose-you";

	@TestConfiguration(proxyBeanMethods = false)
	static class ClockConfiguration {

		@Bean
		@Primary
		MutableClock testClock() {
			return new MutableClock(Instant.now());
		}

	}

	@Autowired
	private MutableClock clock;

	@Autowired
	private InitialAdminCreator initialAdminCreator;

	@Autowired
	private PokemonSyncService syncService;

	private AuthApi auth;

	@BeforeEach
	void setUp() throws Exception {
		clock.set(Instant.now());
		auth = new AuthApi(mockMvc);
		auth.register("ash", PASSWORD);
	}

	@Test
	void theAccessTokenExpiresAfterFifteenMinutesButCanStillBeRefreshed() throws Exception {
		Tokens tokens = auth.signIn("ash", PASSWORD);

		clock.advance(Duration.ofMinutes(14));
		assertThat(currentUserStatus(tokens)).isEqualTo(200);

		clock.advance(Duration.ofMinutes(2));
		assertThat(currentUserStatus(tokens)).isEqualTo(401);

		// 16 minutes in: the refresh token (7 days) still works.
		Tokens refreshed = auth.refreshed(tokens.refreshToken());
		assertThat(currentUserStatus(refreshed)).isEqualTo(200);
	}

	@Test
	void theRefreshTokenExpiresAfterSevenDays() throws Exception {
		Tokens tokens = auth.signIn("ash", PASSWORD);

		// Days after the access token expired, the refresh token still gets a new pair.
		clock.advance(Duration.ofDays(6));
		Tokens refreshed = auth.refreshed(tokens.refreshToken());
		assertThat(currentUserStatus(refreshed)).isEqualTo(200);

		// The new refresh token has its own 7 days; without a refresh, the session is over after them.
		clock.advance(Duration.ofDays(7).plusMinutes(1));
		auth.refresh(refreshed.refreshToken()).andExpect(status().isUnauthorized());
		assertThat(currentUserStatus(refreshed)).isEqualTo(401);
	}

	@Test
	void refreshingKeepsASessionAliveUntilItsMaximumLifetime() throws Exception {
		Tokens tokens = auth.signIn("ash", PASSWORD);
		// Refreshing every 6 days, within the 7-day refresh token lifetime, until the 30-day session ends.
		for (int days = 6; days < 30; days += 6) {
			clock.advance(Duration.ofDays(6));
			tokens = auth.refreshed(tokens.refreshToken());
			assertThat(currentUserStatus(tokens)).isEqualTo(200);
		}

		clock.advance(Duration.ofDays(6));
		assertThat(currentUserStatus(tokens)).isEqualTo(401);
		auth.refresh(tokens.refreshToken()).andExpect(status().isUnauthorized());
	}

	@Test
	void aLockedUsernameCanSignInAgainAfterTheLockout() throws Exception {
		for (int i = 0; i < 5; i++) {
			auth.login("ash", "not-the-password");
		}
		auth.login("ash", PASSWORD).andExpect(status().isTooManyRequests());

		clock.advance(Duration.ofMinutes(15));
		auth.login("ash", PASSWORD).andExpect(status().isOk());
	}

	@Test
	void theInitialAdministratorCanSynchronize() throws Exception {
		// It was created at startup; the base class cleared users since, so run the startup step again.
		initialAdminCreator.run(null);
		Tokens admin = auth.signIn("Professor-Oak", "admin-password-123");

		String run = mockMvc.perform(post("/api/v1/admin/sync/runs").header(HttpHeaders.AUTHORIZATION,
						admin.bearer()))
				.andExpect(status().isAccepted())
				.andReturn().getResponse().getContentAsString();
		long runId = ((Number) JsonPath.read(run, "$.id")).longValue();
		await().atMost(Duration.ofSeconds(20))
				.until(() -> syncService.getRun(runId).status(), runStatus -> runStatus != SyncRunStatus.RUNNING);

		// Running it again leaves the existing administrator alone.
		initialAdminCreator.run(null);
		assertThat(userRepository.count()).isEqualTo(2);
	}

	private int currentUserStatus(Tokens tokens) throws Exception {
		return mockMvc.perform(get("/api/v1/auth/user").header(HttpHeaders.AUTHORIZATION, tokens.bearer()))
				.andReturn().getResponse().getStatus();
	}

}
