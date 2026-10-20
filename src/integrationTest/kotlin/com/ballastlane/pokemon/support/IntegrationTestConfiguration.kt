package com.ballastlane.pokemon.support

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistrar

/**
 * Full integration environment: PostgreSQL, Redis and a WireMock-backed PokeAPI.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import(ContainersConfiguration::class)
class IntegrationTestConfiguration {

    @Bean(destroyMethod = "")
    fun pokeApiContainer(): WireMockContainer = POKE_API

    @Bean
    fun pokeApiProperties(pokeApiContainer: WireMockContainer) = DynamicPropertyRegistrar { registry ->
        registry.add("pokeapi.base-url") { pokeApiContainer.baseUrl + "/api/v2" }
    }

    private companion object {
        val POKE_API = WireMockContainer()
    }
}
