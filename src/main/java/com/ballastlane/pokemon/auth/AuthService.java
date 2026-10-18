package com.ballastlane.pokemon.auth;

import com.ballastlane.pokemon.config.AuthProperties;
import com.ballastlane.pokemon.domain.AppUser;
import com.ballastlane.pokemon.domain.AuthSession;
import com.ballastlane.pokemon.domain.RefreshToken;
import com.ballastlane.pokemon.domain.Role;
import com.ballastlane.pokemon.domain.SessionEndReason;
import com.ballastlane.pokemon.exception.InvalidCredentialsException;
import com.ballastlane.pokemon.exception.InvalidRefreshTokenException;
import com.ballastlane.pokemon.exception.InvalidRequestException;
import com.ballastlane.pokemon.exception.TooManyLoginAttemptsException;
import com.ballastlane.pokemon.exception.UsernameTakenException;
import com.ballastlane.pokemon.localdata.FieldProblem;
import com.ballastlane.pokemon.repository.AppUserRepository;
import com.ballastlane.pokemon.repository.AuthSessionRepository;
import com.ballastlane.pokemon.repository.RefreshTokenRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registration, sign-in, refresh and sign-out.
 * <p>
 * Sign-in starts a session. Refreshing uses up the refresh token and moves the session to its next token
 * generation, which also retires the previous access token. A refresh token presented a second time is treated
 * as stolen and ends the session. Passwords and tokens are never logged.
 */
@Service
public class AuthService {

	private static final Logger log = LoggerFactory.getLogger(AuthService.class);

	/** Generous enough for any token a client might send by mistake (sign-out answers 204 to those). */
	private static final int MAX_TOKEN_LENGTH = 4096;

	private final AppUserRepository userRepository;

	private final AuthSessionRepository sessionRepository;

	private final RefreshTokenRepository refreshTokenRepository;

	private final TokenService tokenService;

	private final PasswordEncoder passwordEncoder;

	private final LoginAttemptLimiter loginAttemptLimiter;

	private final Clock clock;

	private final Duration maxSessionLifetime;

	/** Checked against when the username doesn't exist, so a sign-in takes as long either way. */
	private final String dummyPasswordHash;

	public AuthService(AppUserRepository userRepository, AuthSessionRepository sessionRepository,
			RefreshTokenRepository refreshTokenRepository, TokenService tokenService,
			PasswordEncoder passwordEncoder, LoginAttemptLimiter loginAttemptLimiter, Clock clock,
			AuthProperties properties) {
		this.userRepository = userRepository;
		this.sessionRepository = sessionRepository;
		this.refreshTokenRepository = refreshTokenRepository;
		this.tokenService = tokenService;
		this.passwordEncoder = passwordEncoder;
		this.loginAttemptLimiter = loginAttemptLimiter;
		this.clock = clock;
		this.maxSessionLifetime = properties.maxSessionLifetime();
		this.dummyPasswordHash = passwordEncoder.encode("not-a-real-password-only-for-timing");
	}

	/**
	 * Registers a user with the {@code USER} role. Roles are never taken from the request.
	 *
	 * @throws InvalidRequestException if the username or password breaks the policy
	 * @throws UsernameTakenException if the username is taken (compared ignoring case)
	 */
	@Transactional
	public AppUser register(String username, String password) {
		return createUser(username, password, Set.of(Role.USER));
	}

	/**
	 * Creates a user with the given roles; for registration and the initial administrator.
	 */
	@Transactional
	public AppUser createUser(String username, String password, Set<Role> roles) {
		List<FieldProblem> problems = PasswordPolicy.check(username, password);
		if (!problems.isEmpty()) {
			throw new InvalidRequestException(problems);
		}
		String normalized = PasswordPolicy.normalizeUsername(username);
		if (userRepository.findByUsername(normalized).isPresent()) {
			throw new UsernameTakenException(normalized);
		}
		try {
			AppUser user = userRepository.saveAndFlush(
					new AppUser(normalized, passwordEncoder.encode(password), roles));
			log.info("Registered user {} with roles {}", normalized, roles);
			return user;
		}
		catch (DataIntegrityViolationException ex) {
			// Registered concurrently by another request.
			throw new UsernameTakenException(normalized);
		}
	}

	/**
	 * @throws InvalidRequestException if a field is missing
	 * @throws TooManyLoginAttemptsException if the username is locked after too many failures
	 * @throws InvalidCredentialsException if the username or password is wrong (the same for both)
	 */
	@Transactional(noRollbackFor = InvalidCredentialsException.class)
	public TokenPair login(String username, String password) {
		List<FieldProblem> problems = new ArrayList<>();
		if (username == null || username.isBlank()) {
			problems.add(new FieldProblem("username", "is required"));
		}
		if (password == null || password.isEmpty()) {
			problems.add(new FieldProblem("password", "is required"));
		}
		if (!problems.isEmpty()) {
			throw new InvalidRequestException(problems);
		}

		String normalized = PasswordPolicy.normalizeUsername(username);
		loginAttemptLimiter.checkAllowed(normalized);
		Optional<AppUser> user = userRepository.findByUsername(normalized);
		String hash = user.map(AppUser::getPasswordHash).orElse(dummyPasswordHash);
		boolean matches = passwordEncoder.matches(password, hash);
		if (user.isEmpty() || !matches) {
			if (loginAttemptLimiter.recordFailure(normalized)) {
				log.warn("Too many failed sign-ins for {}; locked for a while", loggable(normalized));
			}
			else {
				log.info("Failed sign-in for {}", loggable(normalized));
			}
			throw new InvalidCredentialsException();
		}

		loginAttemptLimiter.recordSuccess(normalized);
		Instant now = clock.instant();
		AuthSession session = sessionRepository.save(
				new AuthSession(user.get(), now, now.plus(maxSessionLifetime)));
		log.info("User {} signed in (session {})", normalized, session.getId());
		return tokenService.issue(session, user.get(), now);
	}

	/**
	 * Uses up the refresh token and issues a new pair. Ending a session on reuse is committed even though the
	 * call fails, hence {@code noRollbackFor}.
	 *
	 * @throws InvalidRefreshTokenException if the token is unknown, expired, already used, or its session ended
	 */
	@Transactional(noRollbackFor = InvalidRefreshTokenException.class)
	public TokenPair refresh(String refreshToken) {
		requireRefreshToken(refreshToken);
		Instant now = clock.instant();
		RefreshToken token = refreshTokenRepository.findForUpdate(TokenService.hash(refreshToken))
				.orElseThrow(InvalidRefreshTokenException::new);
		AuthSession session = token.getSession();
		if (token.isUsed()) {
			endForReuse(session, now);
			throw new InvalidRefreshTokenException();
		}
		if (token.isExpired(now) || !session.isActive(now)) {
			throw new InvalidRefreshTokenException();
		}

		token.markUsed(now);
		session.nextGeneration();
		AppUser user = session.getUser();
		log.info("Refreshed session {} of user {}", session.getId(), user.getUsername());
		return tokenService.issue(session, user, now);
	}

	/**
	 * Ends the session of the refresh token. Always succeeds for a well-formed request, so the response never
	 * says whether the token was valid.
	 *
	 * @throws InvalidRequestException if the refresh token is missing or blank
	 */
	@Transactional
	public void logout(String refreshToken) {
		requireRefreshToken(refreshToken);
		Instant now = clock.instant();
		refreshTokenRepository.findForUpdate(TokenService.hash(refreshToken)).ifPresent(token -> {
			AuthSession session = token.getSession();
			if (token.isUsed()) {
				endForReuse(session, now);
			}
			else if (session.isActive(now)) {
				session.end(SessionEndReason.LOGOUT, now);
				log.info("Session {} signed out", session.getId());
			}
		});
	}

	@Transactional(readOnly = true)
	public Optional<AppUser> findUser(long userId) {
		return userRepository.findById(userId);
	}

	private void endForReuse(AuthSession session, Instant now) {
		if (session.getEndedAt() == null) {
			session.end(SessionEndReason.REUSE, now);
			log.warn("A used refresh token was presented again; ended session {}", session.getId());
		}
	}

	/**
	 * The username as typed can be anything; only log it if it could be a real one (no line breaks or markup).
	 */
	private static String loggable(String username) {
		return PasswordPolicy.check(username, "x".repeat(PasswordPolicy.MIN_PASSWORD_LENGTH)).stream()
				.anyMatch(problem -> problem.field().equals("username")) ? "<invalid username>" : username;
	}

	private static void requireRefreshToken(String refreshToken) {
		if (refreshToken == null || refreshToken.isBlank()) {
			throw new InvalidRequestException(List.of(new FieldProblem("refreshToken", "is required")));
		}
		if (refreshToken.length() > MAX_TOKEN_LENGTH) {
			throw new InvalidRequestException(List.of(new FieldProblem("refreshToken", "is too long")));
		}
	}

}
