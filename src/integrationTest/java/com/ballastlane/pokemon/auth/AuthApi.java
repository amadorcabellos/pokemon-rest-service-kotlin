package com.ballastlane.pokemon.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Calls the auth API the way a client would.
 */
final class AuthApi {

	private final MockMvc mockMvc;

	AuthApi(MockMvc mockMvc) {
		this.mockMvc = mockMvc;
	}

	ResultActions register(String username, String password) throws Exception {
		return mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content("{ \"username\": \"%s\", \"password\": \"%s\" }".formatted(username, password)));
	}

	ResultActions login(String username, String password) throws Exception {
		return mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{ \"username\": \"%s\", \"password\": \"%s\" }".formatted(username, password)));
	}

	ResultActions refresh(String refreshToken) throws Exception {
		return mockMvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
				.content("{ \"refreshToken\": \"%s\" }".formatted(refreshToken)));
	}

	ResultActions logout(String refreshToken) throws Exception {
		return mockMvc.perform(post("/api/v1/auth/logout").contentType(MediaType.APPLICATION_JSON)
				.content("{ \"refreshToken\": \"%s\" }".formatted(refreshToken)));
	}

	Tokens signIn(String username, String password) throws Exception {
		return Tokens.from(login(username, password).andExpect(status().isOk()));
	}

	Tokens refreshed(String refreshToken) throws Exception {
		return Tokens.from(refresh(refreshToken).andExpect(status().isOk()));
	}

	record Tokens(String accessToken, String refreshToken) {

		String bearer() {
			return "Bearer " + accessToken;
		}

		static Tokens from(ResultActions result) throws Exception {
			String body = result.andReturn().getResponse().getContentAsString();
			return new Tokens(JsonPath.read(body, "$.accessToken"), JsonPath.read(body, "$.refreshToken"));
		}

	}

}
