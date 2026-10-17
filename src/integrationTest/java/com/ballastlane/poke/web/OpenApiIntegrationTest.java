package com.ballastlane.poke.web;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ballastlane.poke.support.AbstractApiIntegrationTest;
import org.junit.jupiter.api.Test;

class OpenApiIntegrationTest extends AbstractApiIntegrationTest {

	@Test
	void servesOpenApiDocument() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.info.title").value("poke-rest-service"))
				.andExpect(jsonPath("$.paths['/api/v1/pokemon/{nameOrId}'].get.summary")
						.value("Get a pokemon by name or id"))
				.andExpect(jsonPath("$.paths['/api/v1/pokemon/{nameOrId}'].get.responses['503']").exists())
				.andExpect(jsonPath("$.paths['/api/v1/pokemon'].get.parameters[?(@.name == 'page')]").exists())
				.andExpect(jsonPath("$.paths['/actuator/health']").doesNotExist());
	}

	@Test
	void servesSwaggerUi() throws Exception {
		mockMvc.perform(get("/swagger-ui/index.html"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("Swagger UI")));
	}

}
