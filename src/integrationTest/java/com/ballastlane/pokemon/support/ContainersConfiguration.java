package com.ballastlane.pokemon.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * PostgreSQL and Redis containers wired into Spring Boot through {@link ServiceConnection}.
 * <p>
 * The containers are JVM-wide singletons so that every cached Spring test context reuses them instead of
 * starting its own set. Testcontainers (Ryuk) removes them when the test JVM exits.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ContainersConfiguration {

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
			DockerImageName.parse("postgres:17-alpine"));

	private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
			.withExposedPorts(6379);

	@Bean(destroyMethod = "")
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return POSTGRES;
	}

	@Bean(destroyMethod = "")
	@ServiceConnection(name = "redis")
	GenericContainer<?> redisContainer() {
		return REDIS;
	}

}
