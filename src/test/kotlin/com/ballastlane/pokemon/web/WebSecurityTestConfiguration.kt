package com.ballastlane.pokemon.web

import com.ballastlane.pokemon.config.AuthProperties
import com.ballastlane.pokemon.config.CorsProperties
import com.ballastlane.pokemon.config.SecurityConfig
import com.ballastlane.pokemon.repository.AuthSessionRepository
import io.mockk.every
import io.mockk.mockk
import java.util.Optional
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import

/**
 * The real security rules for `@WebMvcTest` slices. The session lookup is a mock: slices sign requests in with
 * `@WithMockUser` or `jwt()`, which skip it.
 */
@TestConfiguration
@Import(SecurityConfig::class)
@EnableConfigurationProperties(AuthProperties::class, CorsProperties::class)
class WebSecurityTestConfiguration {

    @Bean
    fun authSessionRepository(): AuthSessionRepository =
        mockk { every { findWithUserById(any()) } returns Optional.empty() }
}
