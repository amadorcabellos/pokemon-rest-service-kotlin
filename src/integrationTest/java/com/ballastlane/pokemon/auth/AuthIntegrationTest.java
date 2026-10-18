package com.ballastlane.pokemon.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ballastlane.pokemon.auth.AuthApi.Tokens;
import com.ballastlane.pokemon.domain.AppUser;
import com.ballastlane.pokemon.domain.Role;
import com.ballastlane.pokemon.support.AbstractApiIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Registration, sign-in, refresh and sign-out end to end, against PostgreSQL.
 */
@ExtendWith(OutputCaptureExtension.class)
class AuthIntegrationTest extends AbstractApiIntegrationTest {

	private static final String PASSWORD = "pikachu-i-choose-you";

	private static final MediaType MERGE_PATCH = MediaType.parseMediaType("application/merge-patch+json");

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private TransactionTemplate transactionTemplate;

	private AuthApi auth;

	@BeforeEach
	void setUp() {
		auth = new AuthApi(mockMvc);
	}

	@Test
	void aRegisteredUserCanUpdateLocalDataButNotSynchronize() throws Exception {
		auth.register(" Ash ", PASSWORD)
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", "/api/v1/auth/user"))
				.andExpect(jsonPath("$.username").value("ash"))
				.andExpect(jsonPath("$.roles[0]").value("USER"))
				.andExpect(jsonPath("$.password").doesNotExist());
		Tokens tokens = auth.signIn("ASH", PASSWORD);
		mockMvc.perform(get("/api/v1/pokemon/pikachu")).andExpect(status().isOk());

		mockMvc.perform(patch("/api/v1/pokemon/pikachu").header(HttpHeaders.AUTHORIZATION, tokens.bearer())
						.contentType(MERGE_PATCH).content("{ \"tags\": [ \"mascot\" ] }"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tags[0]").value("mascot"));
		mockMvc.perform(get("/api/v1/auth/user").header(HttpHeaders.AUTHORIZATION, tokens.bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.username").value("ash"));
		mockMvc.perform(post("/api/v1/admin/sync/runs").header(HttpHeaders.AUTHORIZATION, tokens.bearer()))
				.andExpect(status().isForbidden());
		mockMvc.perform(post("/api/v1/admin/sync/runs"))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string("WWW-Authenticate", "Bearer"));
	}

	@Test
	void registrationRejectsDuplicatesAndInvalidInputAndNeverGrantsAdmin() throws Exception {
		auth.register("ash", PASSWORD).andExpect(status().isCreated());
		auth.register("ASH", PASSWORD).andExpect(status().isConflict());
		auth.register("a", "short")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.length()").value(2));
		mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
						.content("{ \"username\": \"misty\", \"password\": \"%s\", \"roles\": [\"ADMIN\"] }"
								.formatted(PASSWORD)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.roles.length()").value(1));

		assertThat(userRepository.findByUsername("misty")).hasValueSatisfying(
				user -> assertThat(user.getRoles()).containsExactly(Role.USER));
	}

	@Test
	void concurrentRegistrationsOfOneUsernameGiveOneConflict() throws Exception {
		List<Integer> statuses = concurrently(2, i -> auth.register("brock", PASSWORD).andReturn().getResponse()
				.getStatus());

		assertThat(statuses).containsExactlyInAnyOrder(201, 409);
	}

	@Test
	void wrongUsernameAndWrongPasswordGetTheSameAnswer() throws Exception {
		auth.register("ash", PASSWORD).andExpect(status().isCreated());

		String wrongPassword = auth.login("ash", "not-the-password").andExpect(status().isUnauthorized())
				.andReturn().getResponse().getContentAsString();
		String unknownUser = auth.login("gary", PASSWORD).andExpect(status().isUnauthorized())
				.andReturn().getResponse().getContentAsString();

		assertThat(wrongPassword.replace("/api/v1/auth/login", ""))
				.isEqualTo(unknownUser.replace("/api/v1/auth/login", ""));
	}

	@Test
	void refreshEndsTheOldTokensAndReuseEndsTheSession() throws Exception {
		auth.register("ash", PASSWORD);
		Tokens first = auth.signIn("ash", PASSWORD);

		Tokens second = auth.refreshed(first.refreshToken());

		assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());
		assertThat(statusOf(get("/api/v1/auth/user"), second)).isEqualTo(200);
		assertThat(statusOf(get("/api/v1/auth/user"), first)).isEqualTo(401);

		// The old refresh token again: treated as stolen, so the whole session ends.
		auth.refresh(first.refreshToken())
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.title").value("Invalid refresh token"));
		assertThat(statusOf(get("/api/v1/auth/user"), second)).isEqualTo(401);
		auth.refresh(second.refreshToken()).andExpect(status().isUnauthorized());
	}

	@Test
	void concurrentRefreshesWithOneTokenLetOnlyOneThrough() throws Exception {
		auth.register("ash", PASSWORD);
		Tokens tokens = auth.signIn("ash", PASSWORD);

		List<Integer> statuses = concurrently(2, i -> auth.refresh(tokens.refreshToken()).andReturn()
				.getResponse().getStatus());

		assertThat(statuses).containsExactlyInAnyOrder(200, 401);
	}

	@Test
	void signOutNeedsOnlyTheRefreshTokenAndEndsOnlyThatSession() throws Exception {
		auth.register("ash", PASSWORD);
		Tokens phone = auth.signIn("ash", PASSWORD);
		Tokens laptop = auth.signIn("ash", PASSWORD);

		auth.logout(phone.refreshToken()).andExpect(status().isNoContent());

		assertThat(statusOf(get("/api/v1/auth/user"), phone)).isEqualTo(401);
		auth.refresh(phone.refreshToken()).andExpect(status().isUnauthorized());
		assertThat(statusOf(get("/api/v1/auth/user"), laptop)).isEqualTo(200);

		// Signing out again, or with anything else well-formed, says nothing about the token.
		auth.logout(phone.refreshToken()).andExpect(status().isNoContent());
		auth.logout("not-a-real-token").andExpect(status().isNoContent());
		auth.logout(laptop.accessToken()).andExpect(status().isNoContent());
		assertThat(statusOf(get("/api/v1/auth/user"), laptop)).isEqualTo(200);
		mockMvc.perform(post("/api/v1/auth/logout").contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("refreshToken"));
	}

	@Test
	void repeatedFailedSignInsAreThrottled() throws Exception {
		auth.register("ash", PASSWORD);
		for (int i = 0; i < 5; i++) {
			auth.login("ash", "not-the-password").andExpect(status().isUnauthorized());
		}

		auth.login("ash", PASSWORD)
				.andExpect(status().isTooManyRequests())
				.andExpect(header().exists("Retry-After"))
				.andExpect(jsonPath("$.title").value("Too many sign-in attempts"));
		auth.register("misty", PASSWORD);
		auth.login("misty", PASSWORD).andExpect(status().isOk());
	}

	@Test
	void deletedUsersAndRemovedRolesTakeEffectAtOnce() throws Exception {
		String admin = bearerTokenFor("oak", Role.USER, Role.ADMIN);
		String user = bearerTokenFor("gary", Role.USER);
		mockMvc.perform(get("/api/v1/admin/sync/runs/latest").header(HttpHeaders.AUTHORIZATION, admin))
				.andExpect(status().isNotFound());

		transactionTemplate.executeWithoutResult(status -> userRepository.findByUsername("oak")
				.ifPresent(found -> found.removeRole(Role.ADMIN)));
		mockMvc.perform(get("/api/v1/admin/sync/runs/latest").header(HttpHeaders.AUTHORIZATION, admin))
				.andExpect(status().isForbidden());

		userRepository.findByUsername("gary").map(AppUser::getId).ifPresent(userRepository::deleteById);
		mockMvc.perform(get("/api/v1/auth/user").header(HttpHeaders.AUTHORIZATION, user))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void neverStoresOrLogsPasswordsAndTokens(CapturedOutput output) throws Exception {
		auth.register("ash", PASSWORD);
		Tokens first = auth.signIn("ash", PASSWORD);
		Tokens second = auth.refreshed(first.refreshToken());
		auth.login("ash", "wrong-password-123");
		auth.logout(second.refreshToken());

		List<String> hashes = jdbc.queryForList("SELECT token_hash FROM auth_refresh_token", String.class);
		assertThat(hashes).hasSize(2)
				.doesNotContain(first.refreshToken(), second.refreshToken())
				.allSatisfy(hash -> assertThat(hash).hasSize(64));
		assertThat(jdbc.queryForObject("SELECT password_hash FROM app_user", String.class))
				.startsWith("{pbkdf2@SpringSecurity_v5_8}")
				.doesNotContain(PASSWORD);
		// Only the application's log lines: MockMvc's own echo of the test's requests isn't a leak.
		String appLogs = output.getAll().lines()
				.filter(line -> line.contains("[pokemon-rest-service]"))
				.reduce("", (all, line) -> all + line + "\n");
		assertThat(appLogs)
				.contains("User ash signed in")
				.doesNotContain(PASSWORD, "wrong-password-123", first.accessToken(), first.refreshToken(),
						second.accessToken(), second.refreshToken());
	}

	private int statusOf(MockHttpServletRequestBuilder request, Tokens tokens) throws Exception {
		return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, tokens.bearer())).andReturn()
				.getResponse().getStatus();
	}

	private static <T> List<T> concurrently(int count, ThrowingFunction<T> call) throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		try (ExecutorService executor = Executors.newFixedThreadPool(count)) {
			List<Future<T>> results = new ArrayList<>();
			for (int i = 0; i < count; i++) {
				int index = i;
				Callable<T> task = () -> {
					start.await();
					return call.apply(index);
				};
				results.add(executor.submit(task));
			}
			start.countDown();
			List<T> values = new ArrayList<>();
			for (Future<T> result : results) {
				values.add(result.get());
			}
			return values;
		}
	}

	@FunctionalInterface
	private interface ThrowingFunction<T> {

		T apply(int index) throws Exception;

	}

}
