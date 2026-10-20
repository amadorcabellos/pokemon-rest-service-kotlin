package com.ballastlane.pokemon.web;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ballastlane.pokemon.auth.AuthService;
import com.ballastlane.pokemon.auth.TokenPair;
import com.ballastlane.pokemon.domain.AppUser;
import com.ballastlane.pokemon.domain.Role;
import com.ballastlane.pokemon.exception.InvalidCredentialsException;
import com.ballastlane.pokemon.exception.InvalidRefreshTokenException;
import com.ballastlane.pokemon.exception.InvalidRequestException;
import com.ballastlane.pokemon.exception.TooManyLoginAttemptsException;
import com.ballastlane.pokemon.exception.UsernameTakenException;
import com.ballastlane.pokemon.localdata.FieldProblem;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AuthController.class)
@Import(WebSecurityTestConfiguration.class)
class AuthControllerTest {

	private static final Instant EXPIRES = Instant.parse("2026-10-18T10:15:00Z");

	private static final TokenPair TOKENS = new TokenPair("access-token", EXPIRES, "refresh-token", EXPIRES);

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private AuthService authService;

	@Test
	void registersAUser() throws Exception {
		given(authService.register("Ash", "pikachu-i-choose-you")).willReturn(new AppUser("ash", "hash",
				Set.of(Role.USER)));

		mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
						.content("""
								{ "username": "Ash", "password": "pikachu-i-choose-you", "roles": ["ADMIN"] }"""))
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", "/api/v1/auth/user"))
				.andExpect(jsonPath("$.username").value("ash"))
				.andExpect(jsonPath("$.roles[0]").value("USER"))
				.andExpect(jsonPath("$.password").doesNotExist())
				.andExpect(jsonPath("$.passwordHash").doesNotExist());
	}

	@Test
	void registrationListsEveryProblemOrReportsAConflict() throws Exception {
		given(authService.register("a", "short")).willThrow(new InvalidRequestException(List.of(
				new FieldProblem("username", "must be 3 to 50 characters of letters, digits, '.', '_' and '-'"),
				new FieldProblem("password", "must be 12 to 128 characters"))));
		given(authService.register("ash", "pikachu-i-choose-you")).willThrow(new UsernameTakenException("ash"));

		mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
						.content("{ \"username\": \"a\", \"password\": \"short\" }"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.length()").value(2))
				.andExpect(jsonPath("$.errors[1].field").value("password"));
		mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
						.content("{ \"username\": \"ash\", \"password\": \"pikachu-i-choose-you\" }"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.title").value("Username already taken"));
		mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("{"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Malformed request body"));
	}

	@Test
	void signsIn() throws Exception {
		given(authService.login("ash", "pikachu-i-choose-you")).willReturn(TOKENS);

		mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
						.content("{ \"username\": \"ash\", \"password\": \"pikachu-i-choose-you\" }"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").value("access-token"))
				.andExpect(jsonPath("$.tokenType").value("Bearer"))
				.andExpect(jsonPath("$.expiresAt").value("2026-10-18T10:15:00Z"))
				.andExpect(jsonPath("$.refreshToken").value("refresh-token"))
				.andExpect(jsonPath("$.refreshExpiresAt").value("2026-10-18T10:15:00Z"));
	}

	@Test
	void signInFailuresAre401And429() throws Exception {
		given(authService.login("ash", "wrong-password-1")).willThrow(new InvalidCredentialsException());
		given(authService.login("misty", "wrong-password-1"))
				.willThrow(new TooManyLoginAttemptsException(Duration.ofSeconds(90).plusMillis(1)));

		mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
						.content("{ \"username\": \"ash\", \"password\": \"wrong-password-1\" }"))
				.andExpect(status().isUnauthorized())
				.andExpect(header().doesNotExist("WWW-Authenticate"))
				.andExpect(jsonPath("$.title").value("Invalid username or password"));
		mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
						.content("{ \"username\": \"misty\", \"password\": \"wrong-password-1\" }"))
				.andExpect(status().isTooManyRequests())
				.andExpect(header().string("Retry-After", "91"))
				.andExpect(jsonPath("$.title").value("Too many sign-in attempts"));
	}

	@Test
	void refreshesWithoutAnAccessToken() throws Exception {
		given(authService.refresh("refresh-1")).willReturn(TOKENS);
		given(authService.refresh("used")).willThrow(new InvalidRefreshTokenException());

		mockMvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
						.header("Authorization", "Bearer expired-or-garbage")
						.content("{ \"refreshToken\": \"refresh-1\" }"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.refreshToken").value("refresh-token"));
		mockMvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
						.content("{ \"refreshToken\": \"used\" }"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.title").value("Invalid refresh token"));
	}

	@Test
	void signsOutWithoutAnAccessToken() throws Exception {
		mockMvc.perform(post("/api/v1/auth/logout").contentType(MediaType.APPLICATION_JSON)
						.content("{ \"refreshToken\": \"refresh-1\" }"))
				.andExpect(status().isNoContent())
				.andExpect(content().string(""));
		verify(authService).logout("refresh-1");

		willThrow(new InvalidRequestException(List.of(new FieldProblem("refreshToken", "is required"))))
				.given(authService).logout(null);
		mockMvc.perform(post("/api/v1/auth/logout").contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("refreshToken"));
	}

	@Test
	void showsTheSignedInUser() throws Exception {
		given(authService.findUser(7L)).willReturn(new AppUser("ash", "hash", Set.of(Role.USER)));

		mockMvc.perform(get("/api/v1/auth/user").with(jwt().jwt(token -> token.subject("7"))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.username").value("ash"))
				.andExpect(jsonPath("$.passwordHash").doesNotExist());
		mockMvc.perform(get("/api/v1/auth/user"))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string("WWW-Authenticate", "Bearer"));
	}

}
