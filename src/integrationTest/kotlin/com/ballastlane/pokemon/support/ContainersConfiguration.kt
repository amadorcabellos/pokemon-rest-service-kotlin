package com.ballastlane.pokemon.support

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.containers.GenericContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * PostgreSQL and Redis containers wired into Spring Boot through [ServiceConnection].
 *
 * The containers are JVM-wide singletons so that every cached Spring test context reuses them instead of starting
 * its own set. Testcontainers (Ryuk) removes them when the test JVM exits.
 */
@TestConfiguration(proxyBeanMethods = false)
class ContainersConfiguration {

    @Bean(destroyMethod = "")
    @ServiceConnection
    fun postgresContainer(): PostgreSQLContainer = POSTGRES

    @Bean(destroyMethod = "")
    @ServiceConnection(name = "redis")
    fun redisContainer(): GenericContainer<*> = REDIS

    private companion object {

        val POSTGRES = PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))

        val REDIS: GenericContainer<*> =
            GenericContainer<Nothing>(DockerImageName.parse("redis:7-alpine")).apply { exposedPorts = listOf(6379) }
    }
}
