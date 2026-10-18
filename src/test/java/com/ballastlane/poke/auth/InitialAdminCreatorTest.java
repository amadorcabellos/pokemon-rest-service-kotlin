package com.ballastlane.poke.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ballastlane.poke.config.AuthProperties;
import com.ballastlane.poke.domain.AppUser;
import com.ballastlane.poke.domain.Role;
import com.ballastlane.poke.exception.InvalidRequestException;
import com.ballastlane.poke.localdata.FieldProblem;
import com.ballastlane.poke.repository.AppUserRepository;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class InitialAdminCreatorTest {

	private final AppUserRepository userRepository = mock(AppUserRepository.class);

	private final AuthService authService = mock(AuthService.class);

	@Test
	void createsTheConfiguredAdministratorWhenMissing() throws Exception {
		creator("Admin", "admin-password-123").run(null);

		verify(authService).createUser("Admin", "admin-password-123", Set.of(Role.USER, Role.ADMIN));
	}

	@Test
	void leavesAnExistingUserAlone() throws Exception {
		given(userRepository.findByUsername("admin")).willReturn(Optional.of(mock(AppUser.class)));

		creator("admin", "admin-password-123").run(null);

		verify(authService, never()).createUser(anyString(), anyString(), anySet());
	}

	@Test
	void doesNothingWhenNotConfigured() throws Exception {
		creator(null, null).run(null);
		creator("admin", "").run(null);

		verify(authService, never()).createUser(any(), any(), any());
	}

	@Test
	void refusesToStartWithAPasswordThatBreaksThePolicy() {
		willThrow(new InvalidRequestException(List.of(new FieldProblem("password", "must be 12 to 128 characters"))))
				.given(authService).createUser(any(), any(), any());

		assertThatThrownBy(() -> creator("admin", "short").run(null))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("password must be 12 to 128 characters");
	}

	private InitialAdminCreator creator(String username, String password) {
		AuthProperties properties = new AuthProperties("0123456789abcdef0123456789abcdef", Duration.ofMinutes(15),
				Duration.ofDays(7), Duration.ofDays(30),
				new AuthProperties.LoginAttempts(5, Duration.ofMinutes(15), Duration.ofMinutes(15)),
				new AuthProperties.InitialAdmin(username, password));
		return new InitialAdminCreator(properties, userRepository, authService);
	}

}
