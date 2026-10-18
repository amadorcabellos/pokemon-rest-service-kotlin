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
				.andExpect(jsonPath("$.info.title").value("pokemon-rest-service"))
				.andExpect(jsonPath("$.paths['/api/v1/pokemon/{nameOrId}'].get.summary")
						.value("Get pokemon details by name or id"))
				.andExpect(jsonPath("$.components.schemas.PokemonDetails.properties.evolution").exists())
				.andExpect(jsonPath("$.components.schemas.EvolutionStage.properties.evolvesTo").exists())
				.andExpect(jsonPath("$.paths['/api/v1/pokemon/{nameOrId}'].get.responses['503']").exists())
				.andExpect(jsonPath("$.paths['/api/v1/pokemon'].get.summary").value("Browse pokemon"))
				.andExpect(jsonPath("$.paths['/api/v1/pokemon'].get.parameters[?(@.name == 'page')]").exists())
				.andExpect(jsonPath("$.paths['/api/v1/pokemon'].get.parameters[?(@.name == 'size')]").exists())
				.andExpect(jsonPath("$.paths['/api/v1/pokemon'].get.responses['400']").exists())
				.andExpect(jsonPath("$.paths['/api/v1/pokemon'].get.responses['503']").exists())
				.andExpect(jsonPath("$.paths['/api/v1/admin/sync/runs'].post.summary")
						.value("Start a full synchronization"))
				.andExpect(jsonPath("$.paths['/api/v1/admin/sync/runs'].post.responses['202']").exists())
				.andExpect(jsonPath("$.paths['/api/v1/admin/sync/runs'].post.responses['409']").exists())
				.andExpect(jsonPath("$.paths['/api/v1/admin/sync/runs/latest'].get.responses['404']").exists())
				.andExpect(jsonPath("$.paths['/api/v1/admin/sync/runs/{runId}'].get.responses['404']").exists())
				.andExpect(jsonPath("$.paths['/api/v1/admin/sync/pokemon/{nameOrId}'].post.summary")
						.value("Synchronize one pokemon"))
				.andExpect(jsonPath("$.paths['/api/v1/admin/sync/pokemon/{nameOrId}'].post.responses['503']")
						.exists())
				.andExpect(jsonPath("$.components.schemas.SyncRunReport.properties.failures").exists())
				.andExpect(jsonPath("$.paths['/actuator/health']").doesNotExist());
	}

	@Test
	void servesSwaggerUi() throws Exception {
		mockMvc.perform(get("/swagger-ui/index.html"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("Swagger UI")));
	}

}
