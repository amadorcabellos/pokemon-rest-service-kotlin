package com.ballastlane.pokemon.auth

import com.ballastlane.pokemon.config.AuthProperties
import com.ballastlane.pokemon.domain.AppUser
import com.ballastlane.pokemon.domain.Role
import com.ballastlane.pokemon.exception.InvalidRequestException
import com.ballastlane.pokemon.localdata.FieldProblem
import com.ballastlane.pokemon.repository.AppUserRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Duration
import java.util.Optional
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.DefaultApplicationArguments

class InitialAdminCreatorTest {

    private val userRepository = mockk<AppUserRepository>()

    private val authService = mockk<AuthService>()

    @BeforeEach
    fun setUp() {
        every { userRepository.findByUsername(any()) } returns Optional.empty()
        every { authService.createUser(any(), any(), any()) } returns mockk()
    }

    @Test
    fun createsTheConfiguredAdministratorWhenMissing() {
        creator("Admin", "admin-password-123").run(ARGS)

        verify(exactly = 1) { authService.createUser("Admin", "admin-password-123", setOf(Role.USER, Role.ADMIN)) }
    }

    @Test
    fun leavesAnExistingUserAlone() {
        every { userRepository.findByUsername("admin") } returns Optional.of(mockk<AppUser>())

        creator("admin", "admin-password-123").run(ARGS)

        verify(exactly = 0) { authService.createUser(any(), any(), any()) }
    }

    @Test
    fun doesNothingWhenNotConfigured() {
        creator(null, null).run(ARGS)
        creator("admin", "").run(ARGS)

        verify(exactly = 0) { authService.createUser(any(), any(), any()) }
    }

    @Test
    fun refusesToStartWithAPasswordThatBreaksThePolicy() {
        every { authService.createUser(any(), any(), any()) } throws
            InvalidRequestException(listOf(FieldProblem("password", "must be 12 to 128 characters")))

        shouldThrow<IllegalStateException> { creator("admin", "short").run(ARGS) }
            .message shouldContain "password must be 12 to 128 characters"
    }

    private fun creator(username: String?, password: String?): InitialAdminCreator {
        val properties = AuthProperties(
            "0123456789abcdef0123456789abcdef", Duration.ofMinutes(15), Duration.ofDays(7), Duration.ofDays(30),
            AuthProperties.LoginAttempts(5, Duration.ofMinutes(15), Duration.ofMinutes(15)),
            AuthProperties.InitialAdmin(username, password),
        )
        return InitialAdminCreator(properties, userRepository, authService)
    }

    private companion object {
        val ARGS = DefaultApplicationArguments()
    }
}
