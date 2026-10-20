package com.ballastlane.pokemon.config

import com.ballastlane.pokemon.auth.OpenApiSecurity
import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.ExternalDocumentation
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.info.License
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * OpenAPI metadata served at `/v3/api-docs` and rendered by Swagger UI at `/swagger-ui.html`.
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

    @Bean
    fun pokeRestServiceOpenApi(@Value("\${spring.application.name}") applicationName: String): OpenAPI = OpenAPI()
        .info(
            Info()
                .title(applicationName)
                .version("v1")
                .description("Pokemon data from PokeAPI, stored in PostgreSQL and cached in Redis.")
                .license(License().name("PokeAPI fair use policy").url("https://pokeapi.co/docs/v2#fairuse")),
        )
        .externalDocs(ExternalDocumentation().description("PokeAPI documentation").url("https://pokeapi.co/docs/v2"))
        // Protected operations reference this scheme; Swagger UI shows it as "Authorize".
        .components(
            Components().addSecuritySchemes(
                OpenApiSecurity.BEARER,
                SecurityScheme()
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT")
                    .description("Access token from POST /api/v1/auth/login or /api/v1/auth/refresh"),
            ),
        )
}
