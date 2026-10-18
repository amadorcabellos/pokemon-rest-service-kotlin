package com.ballastlane.pokemon.config;

import com.ballastlane.pokemon.auth.OpenApiSecurity;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.ExternalDocumentation;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI metadata served at {@code /v3/api-docs} and rendered by Swagger UI at {@code /swagger-ui.html}.
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

	@Bean
	OpenAPI pokeRestServiceOpenApi(@Value("${spring.application.name}") String applicationName) {
		return new OpenAPI()
				.info(new Info()
						.title(applicationName)
						.version("v1")
						.description("Pokemon data from PokeAPI, stored in PostgreSQL and cached in Redis.")
						.license(new License().name("PokeAPI fair use policy")
								.url("https://pokeapi.co/docs/v2#fairuse")))
				.externalDocs(new ExternalDocumentation()
						.description("PokeAPI documentation")
						.url("https://pokeapi.co/docs/v2"))
				// Protected operations reference this scheme; Swagger UI shows it as "Authorize".
				.components(new Components().addSecuritySchemes(OpenApiSecurity.BEARER, new SecurityScheme()
						.type(SecurityScheme.Type.HTTP)
						.scheme("bearer")
						.bearerFormat("JWT")
						.description("Access token from POST /api/v1/auth/login or /api/v1/auth/refresh")));
	}

}
