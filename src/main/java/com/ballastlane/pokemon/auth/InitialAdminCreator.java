package com.ballastlane.pokemon.auth;

import com.ballastlane.pokemon.config.AuthProperties;
import com.ballastlane.pokemon.domain.Role;
import com.ballastlane.pokemon.exception.InvalidRequestException;
import com.ballastlane.pokemon.exception.UsernameTakenException;
import com.ballastlane.pokemon.repository.AppUserRepository;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Creates the administrator configured with {@code ADMIN_USERNAME} and {@code ADMIN_PASSWORD} at startup, so a
 * fresh installation can run synchronizations. Does nothing unless both are set, and never changes an existing
 * user. A password that breaks the policy stops the application from starting.
 */
@Component
public class InitialAdminCreator implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(InitialAdminCreator.class);

	private final AuthProperties.InitialAdmin initialAdmin;

	private final AppUserRepository userRepository;

	private final AuthService authService;

	public InitialAdminCreator(AuthProperties properties, AppUserRepository userRepository,
			AuthService authService) {
		this.initialAdmin = properties.initialAdmin();
		this.userRepository = userRepository;
		this.authService = authService;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (!initialAdmin.isConfigured()) {
			return;
		}
		String username = PasswordPolicy.normalizeUsername(initialAdmin.username());
		if (userRepository.findByUsername(username).isPresent()) {
			log.info("Initial administrator {} already exists; left unchanged", username);
			return;
		}
		try {
			authService.createUser(initialAdmin.username(), initialAdmin.password(), Set.of(Role.USER, Role.ADMIN));
			log.info("Created initial administrator {}", username);
		}
		catch (UsernameTakenException ex) {
			// Created at the same time by another instance.
			log.info("Initial administrator {} already exists; left unchanged", username);
		}
		catch (InvalidRequestException ex) {
			throw new IllegalStateException("ADMIN_USERNAME / ADMIN_PASSWORD break the policy: " + ex.getProblems()
					.stream().map(problem -> problem.field() + " " + problem.message()).toList(), ex);
		}
	}

}
