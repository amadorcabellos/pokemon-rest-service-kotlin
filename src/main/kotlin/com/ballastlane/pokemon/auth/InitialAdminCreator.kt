package com.ballastlane.pokemon.auth

import com.ballastlane.pokemon.config.AuthProperties
import com.ballastlane.pokemon.domain.Role
import com.ballastlane.pokemon.exception.InvalidRequestException
import com.ballastlane.pokemon.exception.UsernameTakenException
import com.ballastlane.pokemon.repository.AppUserRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

/**
 * Creates the administrator configured with `ADMIN_USERNAME` and `ADMIN_PASSWORD` at startup, so a fresh
 * installation can run synchronizations. Does nothing unless both are set, and never changes an existing user. A
 * password that breaks the policy stops the application from starting.
 */
@Component
class InitialAdminCreator(
    properties: AuthProperties,
    private val userRepository: AppUserRepository,
    private val authService: AuthService,
) : ApplicationRunner {

    private val initialAdmin: AuthProperties.InitialAdmin = properties.initialAdmin

    override fun run(args: ApplicationArguments) {
        val configuredUsername = initialAdmin.username
        if (!initialAdmin.isConfigured || configuredUsername == null) {
            return
        }
        val username = PasswordPolicy.normalizeUsername(configuredUsername)
        if (userRepository.findByUsername(username).isPresent) {
            log.info("Initial administrator {} already exists; left unchanged", username)
            return
        }
        try {
            authService.createUser(configuredUsername, initialAdmin.password, setOf(Role.USER, Role.ADMIN))
            log.info("Created initial administrator {}", username)
        } catch (ex: UsernameTakenException) {
            // Created at the same time by another instance.
            log.info("Initial administrator {} already exists; left unchanged", username)
        } catch (ex: InvalidRequestException) {
            throw IllegalStateException(
                "ADMIN_USERNAME / ADMIN_PASSWORD break the policy: " +
                    ex.problems.map { "${it.field} ${it.message}" },
                ex,
            )
        }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(InitialAdminCreator::class.java)
    }
}
