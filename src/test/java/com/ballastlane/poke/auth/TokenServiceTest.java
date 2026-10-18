package com.ballastlane.poke.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.ballastlane.poke.config.AuthProperties;
import com.ballastlane.poke.domain.AppUser;
import com.ballastlane.poke.domain.AuthSession;
import com.ballastlane.poke.domain.RefreshToken;
import com.ballastlane.poke.domain.Role;
import com.ballastlane.poke.repository.RefreshTokenRepository;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

class TokenServiceTest {

	private static final String SECRET = "token-service-test-secret-0123456789";

	private static final Instant NOW = Instant.parse("2026-10-18T10:00:00Z");

	/** The lifetimes {@code application.yml} and {@link AuthProperties} default to. */
	private static final AuthProperties DEFAULTS = properties(Duration.ofMinutes(15), Duration.ofDays(7));

	private final RefreshTokenRepository repository = mock(RefreshTokenRepository.class);

	private final AppUser user = mock(AppUser.class);

	private NimbusJwtDecoder decoder;

	@BeforeEach
	void setUp() {
		given(user.getId()).willReturn(42L);
		given(user.getRoles()).willReturn(Set.of(Role.USER, Role.ADMIN));
		decoder = NimbusJwtDecoder.withSecretKey(key()).build();
		// Tokens are issued "now" in the test's past; only the signature and claims matter here.
		decoder.setJwtValidator(jwt -> OAuth2TokenValidatorResult.success());
	}

	@Test
	void issuesTokensWithTheDefaultLifetimes() {
		AuthSession session = new AuthSession(user, NOW, NOW.plus(Duration.ofDays(30)));

		TokenPair pair = service(DEFAULTS).issue(session, user, NOW);

		assertThat(pair.accessTokenExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
		assertThat(pair.refreshTokenExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
		Jwt jwt = decoder.decode(pair.accessToken());
		assertThat(jwt.getSubject()).isEqualTo("42");
		assertThat(jwt.getClaimAsString(TokenService.SESSION_CLAIM)).isEqualTo(session.getId().toString());
		assertThat(((Number) jwt.getClaims().get(TokenService.GENERATION_CLAIM)).intValue()).isZero();
		assertThat(jwt.getClaimAsStringList(TokenService.ROLES_CLAIM)).containsExactly("ADMIN", "USER");
		assertThat(jwt.getIssuedAt()).isEqualTo(NOW);
	}

	@Test
	void storesOnlyTheHashOfTheRefreshToken() {
		AuthSession session = new AuthSession(user, NOW, NOW.plus(Duration.ofDays(30)));

		TokenPair pair = service(DEFAULTS).issue(session, user, NOW);

		ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
		verify(repository).save(saved.capture());
		assertThat(saved.getValue().getId()).isEqualTo(TokenService.hash(pair.refreshToken()))
				.hasSize(64)
				.doesNotContain(pair.refreshToken());
		assertThat(pair.refreshToken()).hasSizeGreaterThanOrEqualTo(43);
		assertThat(pair.toString()).doesNotContain(pair.refreshToken()).doesNotContain(pair.accessToken());
	}

	@Test
	void followsTheConfiguredLifetimesButNeverOutlivesTheSession() {
		AuthSession session = new AuthSession(user, NOW, NOW.plus(Duration.ofMinutes(10)));

		TokenPair capped = service(properties(Duration.ofMinutes(15), Duration.ofHours(1))).issue(session, user, NOW);
		TokenPair configured = service(properties(Duration.ofMinutes(2), Duration.ofMinutes(5)))
				.issue(new AuthSession(user, NOW, NOW.plus(Duration.ofDays(1))), user, NOW);

		assertThat(capped.accessTokenExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
		assertThat(capped.refreshTokenExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
		assertThat(configured.accessTokenExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(2)));
		assertThat(configured.refreshTokenExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
	}

	@Test
	void refreshTokensAreUnique() {
		TokenService service = service(DEFAULTS);
		AuthSession session = new AuthSession(user, NOW, NOW.plus(Duration.ofDays(30)));

		assertThat(service.issue(session, user, NOW).refreshToken())
				.isNotEqualTo(service.issue(session, user, NOW).refreshToken());
	}

	private TokenService service(AuthProperties properties) {
		return new TokenService(new NimbusJwtEncoder(new ImmutableSecret<>(key())), repository, properties);
	}

	private static AuthProperties properties(Duration accessTtl, Duration refreshTtl) {
		return new AuthProperties(SECRET, accessTtl, refreshTtl, Duration.ofDays(30),
				new AuthProperties.LoginAttempts(5, Duration.ofMinutes(15), Duration.ofMinutes(15)),
				new AuthProperties.InitialAdmin(null, null));
	}

	private static SecretKeySpec key() {
		return new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
	}

}
