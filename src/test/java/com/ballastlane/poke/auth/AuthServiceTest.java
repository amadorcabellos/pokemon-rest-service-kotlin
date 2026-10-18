package com.ballastlane.poke.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ballastlane.poke.config.AuthProperties;
import com.ballastlane.poke.domain.AppUser;
import com.ballastlane.poke.domain.AuthSession;
import com.ballastlane.poke.domain.RefreshToken;
import com.ballastlane.poke.domain.Role;
import com.ballastlane.poke.domain.SessionEndReason;
import com.ballastlane.poke.exception.InvalidCredentialsException;
import com.ballastlane.poke.exception.InvalidRefreshTokenException;
import com.ballastlane.poke.exception.InvalidRequestException;
import com.ballastlane.poke.exception.TooManyLoginAttemptsException;
import com.ballastlane.poke.exception.UsernameTakenException;
import com.ballastlane.poke.repository.AppUserRepository;
import com.ballastlane.poke.repository.AuthSessionRepository;
import com.ballastlane.poke.repository.RefreshTokenRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

class AuthServiceTest {

	private static final Instant NOW = Instant.parse("2026-10-18T10:00:00Z");

	private static final String PASSWORD = "pikachu-i-choose-you";

	private static final TokenPair TOKENS = new TokenPair("access", NOW.plusSeconds(900), "refresh",
			NOW.plusSeconds(900));

	private final AppUserRepository userRepository = mock(AppUserRepository.class);

	private final AuthSessionRepository sessionRepository = mock(AuthSessionRepository.class);

	private final RefreshTokenRepository refreshTokenRepository = mock(RefreshTokenRepository.class);

	private final TokenService tokenService = mock(TokenService.class);

	private final LoginAttemptLimiter limiter = mock(LoginAttemptLimiter.class);

	private final MutableClock clock = new MutableClock(NOW);

	/** Fast stand-in for PBKDF2. */
	private final PasswordEncoder passwordEncoder = new PasswordEncoder() {

		@Override
		public String encode(CharSequence raw) {
			return "hashed:" + raw;
		}

		@Override
		public boolean matches(CharSequence raw, String encoded) {
			return encoded.equals("hashed:" + raw);
		}

	};

	private AuthService service;

	private AppUser ash;

	@BeforeEach
	void setUp() {
		AuthProperties properties = new AuthProperties("0123456789abcdef0123456789abcdef", Duration.ofMinutes(15),
				Duration.ofDays(7), Duration.ofDays(30),
				new AuthProperties.LoginAttempts(5, Duration.ofMinutes(15), Duration.ofMinutes(15)),
				new AuthProperties.InitialAdmin(null, null));
		service = new AuthService(userRepository, sessionRepository, refreshTokenRepository, tokenService,
				passwordEncoder, limiter, clock, properties);
		ash = new AppUser("ash", "hashed:" + PASSWORD, Set.of(Role.USER));
		given(userRepository.saveAndFlush(any())).willAnswer(invocation -> invocation.getArgument(0));
		given(sessionRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));
		given(tokenService.issue(any(), any(), any())).willReturn(TOKENS);
	}

	@Test
	void registersANormalizedUserWithTheUserRoleAndAHashedPassword() {
		AppUser user = service.register("  Ash ", PASSWORD);

		assertThat(user.getUsername()).isEqualTo("ash");
		assertThat(user.getRoles()).containsExactly(Role.USER);
		assertThat(user.getPasswordHash()).isEqualTo("hashed:" + PASSWORD);
	}

	@Test
	void rejectsInvalidAndTakenUsernames() {
		assertThatThrownBy(() -> service.register("a", "short")).isInstanceOfSatisfying(InvalidRequestException.class,
				ex -> assertThat(ex.getProblems()).hasSize(2));

		given(userRepository.findByUsername("ash")).willReturn(Optional.of(ash));
		assertThatThrownBy(() -> service.register("ASH", PASSWORD)).isInstanceOf(UsernameTakenException.class);
	}

	@Test
	void aConcurrentRegistrationOfTheSameUsernameIsAConflict() {
		willThrow(new DataIntegrityViolationException("duplicate")).given(userRepository).saveAndFlush(any());

		assertThatThrownBy(() -> service.register("ash", PASSWORD)).isInstanceOf(UsernameTakenException.class);
	}

	@Test
	void signsInAndStartsASession() {
		given(userRepository.findByUsername("ash")).willReturn(Optional.of(ash));

		assertThat(service.login(" ASH ", PASSWORD)).isEqualTo(TOKENS);

		ArgumentCaptor<AuthSession> session = ArgumentCaptor.forClass(AuthSession.class);
		verify(sessionRepository).save(session.capture());
		assertThat(session.getValue().getExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(30)));
		verify(limiter).checkAllowed("ash");
		verify(limiter).recordSuccess("ash");
	}

	@Test
	void wrongPasswordAndUnknownUsernameFailTheSameWay() {
		given(userRepository.findByUsername("ash")).willReturn(Optional.of(ash));

		assertThatThrownBy(() -> service.login("ash", "wrong-password-123"))
				.isInstanceOf(InvalidCredentialsException.class)
				.hasMessage("The username or password is incorrect");
		assertThatThrownBy(() -> service.login("misty", PASSWORD))
				.isInstanceOf(InvalidCredentialsException.class)
				.hasMessage("The username or password is incorrect");

		verify(limiter).recordFailure("ash");
		verify(limiter).recordFailure("misty");
		verify(sessionRepository, never()).save(any());
	}

	@Test
	void aLockedUsernameIsRejectedBeforeThePasswordIsChecked() {
		willThrow(new TooManyLoginAttemptsException(Duration.ofMinutes(10))).given(limiter).checkAllowed("ash");

		assertThatThrownBy(() -> service.login("ash", PASSWORD)).isInstanceOf(TooManyLoginAttemptsException.class);
		verify(userRepository, never()).findByUsername(anyString());
	}

	@Test
	void signInNeedsBothFields() {
		assertThatThrownBy(() -> service.login(" ", null)).isInstanceOfSatisfying(InvalidRequestException.class,
				ex -> assertThat(ex.getProblems()).hasSize(2));
	}

	@Test
	void refreshUsesUpTheTokenAndMovesToTheNextGeneration() {
		AuthSession session = new AuthSession(ash, NOW, NOW.plus(Duration.ofDays(30)));
		RefreshToken token = new RefreshToken("hash", session, 0, NOW, NOW.plusSeconds(900));
		given(refreshTokenRepository.findForUpdate(TokenService.hash("refresh-1"))).willReturn(Optional.of(token));
		clock.advance(Duration.ofMinutes(5));

		assertThat(service.refresh("refresh-1")).isEqualTo(TOKENS);

		assertThat(token.isUsed()).isTrue();
		assertThat(session.getTokenGeneration()).isEqualTo(1);
		assertThat(session.getEndedAt()).isNull();
		verify(tokenService).issue(session, ash, NOW.plus(Duration.ofMinutes(5)));
	}

	@Test
	void reusingARefreshTokenEndsTheSession() {
		AuthSession session = new AuthSession(ash, NOW, NOW.plus(Duration.ofDays(30)));
		RefreshToken token = new RefreshToken("hash", session, 0, NOW, NOW.plusSeconds(900));
		token.markUsed(NOW);
		given(refreshTokenRepository.findForUpdate(any())).willReturn(Optional.of(token));

		assertThatThrownBy(() -> service.refresh("refresh-1")).isInstanceOf(InvalidRefreshTokenException.class);

		assertThat(session.getEndReason()).isEqualTo(SessionEndReason.REUSE);
		verify(tokenService, never()).issue(any(), any(), any());
	}

	@Test
	void rejectsUnknownExpiredAndEndedRefreshTokens() {
		assertThatThrownBy(() -> service.refresh("unknown")).isInstanceOf(InvalidRefreshTokenException.class);

		AuthSession session = new AuthSession(ash, NOW, NOW.plus(Duration.ofDays(30)));
		RefreshToken expired = new RefreshToken("expired", session, 0, NOW, NOW.plusSeconds(900));
		given(refreshTokenRepository.findForUpdate(TokenService.hash("expired"))).willReturn(Optional.of(expired));
		clock.advance(Duration.ofMinutes(15));
		assertThatThrownBy(() -> service.refresh("expired")).isInstanceOf(InvalidRefreshTokenException.class);

		AuthSession ended = new AuthSession(ash, NOW, NOW.plus(Duration.ofDays(30)));
		ended.end(SessionEndReason.LOGOUT, NOW);
		RefreshToken fresh = new RefreshToken("fresh", ended, 0, clock.instant(), clock.instant().plusSeconds(900));
		given(refreshTokenRepository.findForUpdate(TokenService.hash("fresh"))).willReturn(Optional.of(fresh));
		assertThatThrownBy(() -> service.refresh("fresh")).isInstanceOf(InvalidRefreshTokenException.class);

		assertThat(expired.isUsed()).isFalse();
		verify(tokenService, never()).issue(any(), any(), any());
	}

	@Test
	void aSessionCantBeRefreshedPastItsMaximumLifetime() {
		AuthSession session = new AuthSession(ash, NOW, NOW.plus(Duration.ofMinutes(10)));
		RefreshToken token = new RefreshToken("hash", session, 0, NOW, NOW.plus(Duration.ofMinutes(15)));
		given(refreshTokenRepository.findForUpdate(any())).willReturn(Optional.of(token));
		clock.advance(Duration.ofMinutes(11));

		assertThatThrownBy(() -> service.refresh("refresh-1")).isInstanceOf(InvalidRefreshTokenException.class);
	}

	@Test
	void logoutEndsTheSessionOfTheRefreshToken() {
		AuthSession session = new AuthSession(ash, NOW, NOW.plus(Duration.ofDays(30)));
		given(refreshTokenRepository.findForUpdate(any()))
				.willReturn(Optional.of(new RefreshToken("hash", session, 0, NOW, NOW.plusSeconds(900))));

		service.logout("refresh-1");

		assertThat(session.getEndReason()).isEqualTo(SessionEndReason.LOGOUT);
		assertThat(session.isActive(NOW)).isFalse();
	}

	@Test
	void logoutAcceptsAnyWellFormedTokenButEndsTheSessionOnReuse() {
		service.logout("unknown");

		AuthSession session = new AuthSession(ash, NOW, NOW.plus(Duration.ofDays(30)));
		RefreshToken used = new RefreshToken("hash", session, 0, NOW, NOW.plusSeconds(900));
		used.markUsed(NOW);
		given(refreshTokenRepository.findForUpdate(TokenService.hash("used"))).willReturn(Optional.of(used));
		service.logout("used");
		assertThat(session.getEndReason()).isEqualTo(SessionEndReason.REUSE);

		assertThatThrownBy(() -> service.logout(" ")).isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> service.logout("x".repeat(4097))).isInstanceOf(InvalidRequestException.class);
	}

}
