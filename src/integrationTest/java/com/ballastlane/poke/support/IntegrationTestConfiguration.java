package com.ballastlane.poke.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistrar;

/**
 * Full integration environment: PostgreSQL, Redis and a WireMock-backed PokeAPI.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import(ContainersConfiguration.class)
public class IntegrationTestConfiguration {

	private static final WireMockContainer POKE_API = new WireMockContainer();

	@Bean(destroyMethod = "")
	WireMockContainer pokeApiContainer() {
		return POKE_API;
	}

	@Bean
	DynamicPropertyRegistrar pokeApiProperties(WireMockContainer pokeApiContainer) {
		return registry -> registry.add("pokeapi.base-url", () -> pokeApiContainer.getBaseUrl() + "/api/v2");
	}

}
