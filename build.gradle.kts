plugins {
	java
	id("org.springframework.boot") version "4.1.1"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "com.ballastlane"
version = "0.0.1-SNAPSHOT"
description = "REST API that integrates with PokeAPI, backed by PostgreSQL and Redis"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

val resilience4jVersion = "2.4.0"
val springdocVersion = "3.1.1"

repositories {
	mavenCentral()
}

// Unit tests live in src/test (fast, no Docker).
// Integration tests live in src/integrationTest (Testcontainers, require Docker).
testing {
	suites {
		val test by getting(JvmTestSuite::class) {
			useJUnitJupiter()
		}
		register<JvmTestSuite>("integrationTest") {
			useJUnitJupiter()
			dependencies {
				implementation(project())
			}
			targets {
				all {
					testTask.configure {
						shouldRunAfter(test)
					}
				}
			}
		}
	}
}

val integrationTestImplementation by configurations.getting {
	extendsFrom(configurations.implementation.get(), configurations.testImplementation.get())
}
val integrationTestRuntimeOnly by configurations.getting {
	extendsFrom(configurations.runtimeOnly.get(), configurations.testRuntimeOnly.get())
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-aspectj")
	implementation("org.springframework.boot:spring-boot-starter-cache")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-data-redis")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.springframework.boot:spring-boot-starter-restclient")
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.flywaydb:flyway-database-postgresql")
	implementation("io.github.resilience4j:resilience4j-spring-boot4:$resilience4jVersion")
	implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:$springdocVersion")
	runtimeOnly("org.postgresql:postgresql")

	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-restclient-test")
	testImplementation("org.springframework.boot:spring-boot-starter-security-test")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")

	integrationTestImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	integrationTestImplementation("org.springframework.boot:spring-boot-starter-data-redis-test")
	integrationTestImplementation("org.springframework.boot:spring-boot-testcontainers")
	integrationTestImplementation("org.testcontainers:testcontainers-junit-jupiter")
	integrationTestImplementation("org.testcontainers:testcontainers-postgresql")
}

tasks.named("check") {
	dependsOn(testing.suites.named("integrationTest"))
}

tasks.withType<Test>().configureEach {
	testLogging {
		events("passed", "skipped", "failed")
		exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
	}
}

// `./gradlew bootTestRun` starts the app with throwaway PostgreSQL/Redis containers.
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootTestRun") {
	classpath = sourceSets["integrationTest"].runtimeClasspath
	mainClass = "com.ballastlane.pokemon.TestPokeRestServiceApplication"
}
