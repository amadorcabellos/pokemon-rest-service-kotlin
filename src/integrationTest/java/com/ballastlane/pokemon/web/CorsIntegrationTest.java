package com.ballastlane.pokemon.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ballastlane.pokemon.support.AbstractApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

class CorsIntegrationTest extends AbstractApiIntegrationTest {

	private static final String FRONTEND = "http://localhost:3000";

	@Test
	void answersPreflightForAProtectedRouteWithoutAToken() throws Exception {
		mockMvc.perform(options("/api/v1/pokemon/pikachu")
						.header(HttpHeaders.ORIGIN, FRONTEND)
						.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PATCH")
						.header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type,if-match"))
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONTEND))
				.andExpect(header().stringValues(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS,
						hasItem(containsString("PATCH"))))
				.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
	}

	@Test
	void exposesHeadersOnActualRequests() throws Exception {
		mockMvc.perform(post("/api/v1/admin/sync/runs").header(HttpHeaders.ORIGIN, FRONTEND))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONTEND))
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,
						containsString(HttpHeaders.ETAG)));
	}

	@Test
	void rejectsUnknownOrigins() throws Exception {
		mockMvc.perform(options("/api/v1/pokemon/pikachu")
						.header(HttpHeaders.ORIGIN, "https://evil.example")
						.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
				.andExpect(status().isForbidden())
				.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
	}

}
