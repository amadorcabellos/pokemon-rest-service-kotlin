package com.ballastlane.pokemon.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.net.URI
import java.time.Duration
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration
import org.springframework.context.annotation.Configuration
import org.springframework.util.unit.DataSize

/**
 * The Kotlin properties classes bind the same defaults, overrides and validation rules as the Java records did.
 */
class ConfigurationPropertiesBindingTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(
        AuthProperties::class, CacheProperties::class, CatalogProperties::class, CorsProperties::class,
        LocalDataProperties::class, PokeApiProperties::class, SyncProperties::class,
    )
    class AllProperties

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration::class.java))
        .withUserConfiguration(AllProperties::class.java)
        .withPropertyValues("app.security.jwt-secret=$SECRET", "pokeapi.base-url=https://pokeapi.test/api/v2")

    @Test
    fun bindsTheDefaultsWhenOnlyTheRequiredKeysAreSet() {
        contextRunner.run { context ->
            context.getBean(AuthProperties::class.java) shouldBe AuthProperties(
                SECRET, Duration.ofMinutes(15), Duration.ofDays(7), Duration.ofDays(30),
                AuthProperties.LoginAttempts(5, Duration.ofMinutes(15), Duration.ofMinutes(15)),
                AuthProperties.InitialAdmin(null, null),
            )
            context.getBean(CacheProperties::class.java).pokemonTtl shouldBe Duration.ofHours(1)
            context.getBean(CatalogProperties::class.java) shouldBe CatalogProperties(20, 50)
            context.getBean(CorsProperties::class.java) shouldBe
                CorsProperties(listOf("http://localhost:3000"), Duration.ofMinutes(30))
            context.getBean(LocalDataProperties::class.java).maxRequestSize shouldBe DataSize.ofKilobytes(64)
            context.getBean(PokeApiProperties::class.java) shouldBe PokeApiProperties(
                URI.create("https://pokeapi.test/api/v2"), Duration.ofSeconds(5), Duration.ofSeconds(10),
            )
            context.getBean(SyncProperties::class.java) shouldBe SyncProperties(
                50, Duration.ofMillis(100), Duration.ofSeconds(5), 12, SyncProperties.Schedule(false, "0 0 3 * * *"),
            )
        }
    }

    @Test
    fun bindsOverridesIncludingNestedObjectsListsAndDurations() {
        contextRunner.withPropertyValues(
            "app.security.access-token-ttl=2m",
            "app.security.login-attempts.max-failures=3",
            "app.security.initial-admin.username=admin",
            "app.security.initial-admin.password=admin-password-123",
            "app.cache.pokemon-ttl=10m",
            "app.catalog.max-page-size=100",
            "app.cors.allowed-origins=https://a.test,https://b.test",
            "app.local-data.max-request-size=1KB",
            "pokeapi.read-timeout=2s",
            "app.sync.batch-size=10",
            "app.sync.schedule.enabled=true",
        ).run { context ->
            val auth = context.getBean(AuthProperties::class.java)
            auth.accessTokenTtl shouldBe Duration.ofMinutes(2)
            auth.refreshTokenTtl shouldBe Duration.ofDays(7)
            auth.loginAttempts shouldBe AuthProperties.LoginAttempts(3, Duration.ofMinutes(15), Duration.ofMinutes(15))
            auth.initialAdmin.isConfigured shouldBe true
            context.getBean(CacheProperties::class.java).pokemonTtl shouldBe Duration.ofMinutes(10)
            context.getBean(CatalogProperties::class.java) shouldBe CatalogProperties(20, 100)
            context.getBean(CorsProperties::class.java).allowedOrigins shouldBe listOf("https://a.test", "https://b.test")
            context.getBean(LocalDataProperties::class.java).maxRequestSize shouldBe DataSize.ofKilobytes(1)
            context.getBean(PokeApiProperties::class.java).readTimeout shouldBe Duration.ofSeconds(2)
            context.getBean(SyncProperties::class.java).batchSize shouldBe 10
            context.getBean(SyncProperties::class.java).schedule shouldBe SyncProperties.Schedule(true, "0 0 3 * * *")
        }
    }

    @Test
    fun anEmptyCorsListDisablesCors() {
        contextRunner.withPropertyValues("app.cors.allowed-origins=").run { context ->
            context.startupFailure.shouldBeNull()
            context.getBean(CorsProperties::class.java).allowedOrigins shouldBe emptyList()
        }
    }

    @Test
    fun rejectsAMissingOrShortSecretAndInvalidValues() {
        listOf(
            "app.security.jwt-secret=" to "jwtSecret",
            "app.security.jwt-secret=too-short" to "must be at least 32 bytes",
            "app.catalog.default-page-size=0" to "defaultPageSize",
            "app.sync.batch-size=101" to "batchSize",
            "app.sync.schedule.cron= " to "cron",
            "app.security.login-attempts.max-failures=0" to "maxFailures",
        ).forEach { (property, message) ->
            contextRunner.withPropertyValues(property).run { context ->
                messages(context.startupFailure.shouldNotBeNull()) shouldContain message
            }
        }
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration::class.java))
            .withUserConfiguration(AllProperties::class.java)
            .withPropertyValues("app.security.jwt-secret=$SECRET")
            .run { context -> messages(context.startupFailure.shouldNotBeNull()) shouldContain "baseUrl" }
    }

    @Test
    fun aShortSecretIsRejectedWhenConstructedDirectly() {
        shouldThrow<IllegalArgumentException> { AuthProperties(jwtSecret = "short") }
            .message shouldBe "app.security.jwt-secret (JWT_SECRET) must be at least 32 bytes"
    }

    private fun messages(failure: Throwable): String =
        generateSequence(failure) { it.cause }.joinToString(" | ") { it.message.orEmpty() }

    private companion object {
        const val SECRET = "binding-test-secret-0123456789abcdef"
    }
}
