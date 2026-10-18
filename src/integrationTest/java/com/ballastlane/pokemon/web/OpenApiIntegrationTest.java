package com.ballastlane.pokemon.web;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ballastlane.pokemon.support.AbstractApiIntegrationTest;
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
				.andExpect(jsonPath("$.components.schemas.PokemonDetails.properties.localData").exists())
				.andExpect(jsonPath("$.components.schemas.LocalData.properties.version").exists())
				.andExpect(jsonPath("$.paths['/api/v1/pokemon/{nameOrId}'].get.responses['200'].headers.ETag").exists())
				.andExpect(jsonPath("$.paths['/api/v1/pokemon/{nameOrId}'].put.summary")
						.value("Replace the local data of a pokemon"))
				.andExpect(jsonPath("$.paths['/api/v1/pokemon/{nameOrId}'].put.requestBody.content['application/json']"
						+ ".schema['$ref']").value("#/components/schemas/LocalDataRequest"))
				.andExpect(jsonPath("$.paths['/api/v1/pokemon/{nameOrId}'].put.parameters[?(@.name == 'If-Match')]")
						.exists())
				.andExpect(jsonPath("$.paths['/api/v1/pokemon/{nameOrId}'].patch.summary")
						.value("Partially update the local data of a pokemon"))
				.andExpect(jsonPath("$.paths['/api/v1/pokemon/{nameOrId}'].patch.requestBody.content"
						+ "['application/merge-patch+json']").exists())
				.andExpect(jsonPath("$.paths['/api/v1/pokemon/{nameOrId}'].patch.responses['400']").exists())
				.andExpect(jsonPath("$.paths['/api/v1/pokemon/{nameOrId}'].patch.responses['404']").exists())
				.andExpect(jsonPath("$.paths['/api/v1/pokemon/{nameOrId}'].patch.responses['412']").exists())
				.andExpect(jsonPath("$.paths['/api/v1/pokemon/{nameOrId}'].patch.responses['413']").exists())
				.andExpect(jsonPath("$.paths['/api/v1/pokemon/{nameOrId}'].put.responses['415']").exists())
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
				.andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
				.andExpect(jsonPath("$.components.securitySchemes.bearerAuth.bearerFormat").value("JWT"))
				.andExpect(jsonPath("$.paths['/api/v1/admin/sync/runs'].post.security[0].bearerAuth").exists())
				.andExpect(jsonPath("$.paths['/api/v1/admin/sync/runs'].post.responses['401']").exists())
				.andExpect(jsonPath("$.paths['/api/v1/admin/sync/runs'].post.responses['403']").exists())
				.andExpect(jsonPath("$.paths['/api/v1/admin/sync/runs/{runId}'].get.security[0].bearerAuth").exists())
				.andExpect(jsonPath("$.paths['/api/v1/admin/sync/runs/latest'].get.security[0].bearerAuth").exists())
				.andExpect(jsonPath("$.paths['/api/v1/admin/sync/pokemon/{nameOrId}'].post.security[0].bearerAuth")
						.exists())
				.andExpect(jsonPath("$.paths['/api/v1/pokemon/{nameOrId}'].put.security[0].bearerAuth").exists())
				.andExpect(jsonPath("$.paths['/api/v1/pokemon/{nameOrId}'].patch.security[0].bearerAuth").exists())
				.andExpect(jsonPath("$.paths['/api/v1/pokemon/{nameOrId}'].patch.responses['403']").exists())
				.andExpect(jsonPath("$.paths['/api/v1/pokemon/{nameOrId}'].get.security").doesNotExist())
				.andExpect(jsonPath("$.paths['/api/v1/auth/register'].post.responses['409']").exists())
				.andExpect(jsonPath("$.paths['/api/v1/auth/login'].post.responses['429']").exists())
				.andExpect(jsonPath("$.paths['/api/v1/auth/login'].post.security").doesNotExist())
				.andExpect(jsonPath("$.paths['/api/v1/auth/refresh'].post.responses['401']").exists())
				.andExpect(jsonPath("$.paths['/api/v1/auth/logout'].post.responses['204']").exists())
				.andExpect(jsonPath("$.paths['/api/v1/auth/user'].get.security[0].bearerAuth").exists())
				.andExpect(jsonPath("$.paths['/actuator/health']").doesNotExist());
	}

	@Test
	void servesSwaggerUi() throws Exception {
		mockMvc.perform(get("/swagger-ui/index.html"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("Swagger UI")));
	}

}
