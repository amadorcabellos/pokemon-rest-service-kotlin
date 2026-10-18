package com.ballastlane.poke.auth;

import com.ballastlane.poke.config.AuthProperties;
import com.ballastlane.poke.domain.AppUser;
import com.ballastlane.poke.domain.AuthSession;
import com.ballastlane.poke.domain.RefreshToken;
import com.ballastlane.poke.domain.Role;
import com.ballastlane.poke.repository.RefreshTokenRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/**
 * Issues the token pair of a session.
 * <ul>
 * <li>Access token: a JWT signed with HS256, carrying the user ({@code sub}), the session ({@code sid}) and its
 * token generation ({@code gen}).</li>
 * <li>Refresh token: 256 random bits, base64url encoded. Only its SHA-256 hash is stored.</li>
 * </ul>
 * Neither outlives the session's maximum lifetime.
 */
@Component
public class TokenService {

	public static final String SESSION_CLAIM = "sid";

	public static final String GENERATION_CLAIM = "gen";

	public static final String ROLES_CLAIM = "roles";

	private static final int REFRESH_TOKEN_BYTES = 32;

	private final JwtEncoder jwtEncoder;

	private final RefreshTokenRepository refreshTokenRepository;

	private final Duration accessTokenTtl;

	private final Duration refreshTokenTtl;

	private final SecureRandom random = new SecureRandom();

	public TokenService(JwtEncoder jwtEncoder, RefreshTokenRepository refreshTokenRepository,
			AuthProperties properties) {
		this.jwtEncoder = jwtEncoder;
		this.refreshTokenRepository = refreshTokenRepository;
		this.accessTokenTtl = properties.accessTokenTtl();
		this.refreshTokenTtl = properties.refreshTokenTtl();
	}

	/**
	 * Issues tokens for the session's current generation and stores the refresh token's hash.
	 */
	public TokenPair issue(AuthSession session, AppUser user, Instant at) {
		// JWT times are whole seconds; issuing on a whole second keeps the reported expiry exact.
		Instant now = at.truncatedTo(ChronoUnit.SECONDS);
		Instant accessExpiresAt = earliest(now.plus(accessTokenTtl), session.getExpiresAt());
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.subject(user.getId().toString())
				.issuedAt(now)
				.expiresAt(accessExpiresAt)
				.claim(SESSION_CLAIM, session.getId().toString())
				.claim(GENERATION_CLAIM, session.getTokenGeneration())
				.claim(ROLES_CLAIM, user.getRoles().stream().map(Role::name).sorted().toList())
				.build();
		String accessToken = jwtEncoder
				.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
				.getTokenValue();

		byte[] bytes = new byte[REFRESH_TOKEN_BYTES];
		random.nextBytes(bytes);
		String refreshToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		Instant refreshExpiresAt = earliest(now.plus(refreshTokenTtl), session.getExpiresAt());
		refreshTokenRepository.save(new RefreshToken(hash(refreshToken), session, session.getTokenGeneration(), now,
				refreshExpiresAt));

		return new TokenPair(accessToken, accessExpiresAt, refreshToken, refreshExpiresAt);
	}

	/**
	 * SHA-256, hex encoded. A fast hash is enough: unlike a password, the token is 256 random bits.
	 */
	public static String hash(String refreshToken) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(refreshToken.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is not available", ex);
		}
	}

	private static Instant earliest(Instant first, Instant second) {
		return first.isBefore(second) ? first : second;
	}

}
