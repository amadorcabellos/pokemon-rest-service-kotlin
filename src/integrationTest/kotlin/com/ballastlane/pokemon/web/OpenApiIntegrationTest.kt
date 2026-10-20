package com.ballastlane.pokemon.web

import com.ballastlane.pokemon.support.AbstractApiIntegrationTest
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.get

class OpenApiIntegrationTest : AbstractApiIntegrationTest() {

    @Test
    fun servesOpenApiDocument() {
        mockMvc.get("/v3/api-docs").andExpect {
            status { isOk() }
            jsonPath("$.info.title") { value("pokemon-rest-service") }
            jsonPath("$.paths['/api/v1/pokemon/{nameOrId}'].get.summary") { value("Get pokemon details by name or id") }
            jsonPath("$.components.schemas.PokemonDetails.properties.evolution") { exists() }
            jsonPath("$.components.schemas.EvolutionStage.properties.evolvesTo") { exists() }
            jsonPath("$.paths['/api/v1/pokemon/{nameOrId}'].get.responses['503']") { exists() }
            jsonPath("$.components.schemas.PokemonDetails.properties.localData") { exists() }
            jsonPath("$.components.schemas.LocalData.properties.version") { exists() }
            jsonPath("$.paths['/api/v1/pokemon/{nameOrId}'].get.responses['200'].headers.ETag") { exists() }
            jsonPath("$.paths['/api/v1/pokemon/{nameOrId}'].put.summary") { value("Replace the local data of a pokemon") }
            jsonPath("$.paths['/api/v1/pokemon/{nameOrId}'].put.requestBody.content['application/json'].schema['\$ref']") {
                value("#/components/schemas/LocalDataRequest")
            }
            jsonPath("$.paths['/api/v1/pokemon/{nameOrId}'].put.parameters[?(@.name == 'If-Match')]") { exists() }
            jsonPath("$.paths['/api/v1/pokemon/{nameOrId}'].patch.summary") { value("Partially update the local data of a pokemon") }
            jsonPath("$.paths['/api/v1/pokemon/{nameOrId}'].patch.requestBody.content['application/merge-patch+json']") { exists() }
            jsonPath("$.paths['/api/v1/pokemon/{nameOrId}'].patch.responses['400']") { exists() }
            jsonPath("$.paths['/api/v1/pokemon/{nameOrId}'].patch.responses['404']") { exists() }
            jsonPath("$.paths['/api/v1/pokemon/{nameOrId}'].patch.responses['412']") { exists() }
            jsonPath("$.paths['/api/v1/pokemon/{nameOrId}'].patch.responses['413']") { exists() }
            jsonPath("$.paths['/api/v1/pokemon/{nameOrId}'].put.responses['415']") { exists() }
            jsonPath("$.paths['/api/v1/pokemon'].get.summary") { value("Browse pokemon") }
            jsonPath("$.paths['/api/v1/pokemon'].get.parameters[?(@.name == 'page')]") { exists() }
            jsonPath("$.paths['/api/v1/pokemon'].get.parameters[?(@.name == 'size')]") { exists() }
            jsonPath("$.paths['/api/v1/pokemon'].get.responses['400']") { exists() }
            jsonPath("$.paths['/api/v1/pokemon'].get.responses['503']") { exists() }
            jsonPath("$.paths['/api/v1/admin/sync/runs'].post.summary") { value("Start a full synchronization") }
            jsonPath("$.paths['/api/v1/admin/sync/runs'].post.responses['202']") { exists() }
            jsonPath("$.paths['/api/v1/admin/sync/runs'].post.responses['409']") { exists() }
            jsonPath("$.paths['/api/v1/admin/sync/runs/latest'].get.responses['404']") { exists() }
            jsonPath("$.paths['/api/v1/admin/sync/runs/{runId}'].get.responses['404']") { exists() }
            jsonPath("$.paths['/api/v1/admin/sync/pokemon/{nameOrId}'].post.summary") { value("Synchronize one pokemon") }
            jsonPath("$.paths['/api/v1/admin/sync/pokemon/{nameOrId}'].post.responses['503']") { exists() }
            jsonPath("$.components.schemas.SyncRunReport.properties.failures") { exists() }
            jsonPath("$.components.securitySchemes.bearerAuth.scheme") { value("bearer") }
            jsonPath("$.components.securitySchemes.bearerAuth.bearerFormat") { value("JWT") }
            jsonPath("$.paths['/api/v1/admin/sync/runs'].post.security[0].bearerAuth") { exists() }
            jsonPath("$.paths['/api/v1/admin/sync/runs'].post.responses['401']") { exists() }
            jsonPath("$.paths['/api/v1/admin/sync/runs'].post.responses['403']") { exists() }
            jsonPath("$.paths['/api/v1/admin/sync/runs/{runId}'].get.security[0].bearerAuth") { exists() }
            jsonPath("$.paths['/api/v1/admin/sync/runs/latest'].get.security[0].bearerAuth") { exists() }
            jsonPath("$.paths['/api/v1/admin/sync/pokemon/{nameOrId}'].post.security[0].bearerAuth") { exists() }
            jsonPath("$.paths['/api/v1/pokemon/{nameOrId}'].put.security[0].bearerAuth") { exists() }
            jsonPath("$.paths['/api/v1/pokemon/{nameOrId}'].patch.security[0].bearerAuth") { exists() }
            jsonPath("$.paths['/api/v1/pokemon/{nameOrId}'].patch.responses['403']") { exists() }
            jsonPath("$.paths['/api/v1/pokemon/{nameOrId}'].get.security") { doesNotExist() }
            jsonPath("$.paths['/api/v1/auth/register'].post.responses['409']") { exists() }
            jsonPath("$.paths['/api/v1/auth/login'].post.responses['429']") { exists() }
            jsonPath("$.paths['/api/v1/auth/login'].post.security") { doesNotExist() }
            jsonPath("$.paths['/api/v1/auth/refresh'].post.responses['401']") { exists() }
            jsonPath("$.paths['/api/v1/auth/logout'].post.responses['204']") { exists() }
            jsonPath("$.paths['/api/v1/auth/user'].get.security[0].bearerAuth") { exists() }
            jsonPath("$.paths['/actuator/health']") { doesNotExist() }
        }
    }

    @Test
    fun servesSwaggerUi() {
        mockMvc.get("/swagger-ui/index.html").andExpect {
            status { isOk() }
            content { string(containsString("Swagger UI")) }
        }
    }
}
