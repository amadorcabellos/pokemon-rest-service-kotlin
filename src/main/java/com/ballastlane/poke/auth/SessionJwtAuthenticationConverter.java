package com.ballastlane.poke.auth;

import com.ballastlane.poke.domain.AuthSession;
import com.ballastlane.poke.repository.AuthSessionRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns a valid access token (signature and expiry already checked) into an authentication, after checking it
 * against the database: its session must still be active and at the token's generation, and its user must
 * still exist. Roles come from the database, not the token, so role changes take effect at once.
 */
public class SessionJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

	private final AuthSessionRepository sessionRepository;

	private final Clock clock;

	public SessionJwtAuthenticationConverter(AuthSessionRepository sessionRepository, Clock clock) {
		this.sessionRepository = sessionRepository;
		this.clock = clock;
	}

	@Override
	@Transactional(readOnly = true)
	public AbstractAuthenticationToken convert(Jwt jwt) {
		UUID sessionId = sessionId(jwt);
		Object generation = jwt.getClaims().get(TokenService.GENERATION_CLAIM);
		AuthSession session = sessionRepository.findWithUserById(sessionId)
				.filter(candidate -> candidate.isActive(clock.instant()))
				.filter(candidate -> generation instanceof Number number
						&& number.intValue() == candidate.getTokenGeneration())
				.filter(candidate -> candidate.getUser().getId().toString().equals(jwt.getSubject()))
				.orElseThrow(SessionJwtAuthenticationConverter::invalid);
		List<SimpleGrantedAuthority> authorities = session.getUser().getRoles().stream()
				.map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
				.toList();
		return new JwtAuthenticationToken(jwt, authorities, session.getUser().getUsername());
	}

	private static UUID sessionId(Jwt jwt) {
		try {
			return UUID.fromString(jwt.getClaimAsString(TokenService.SESSION_CLAIM));
		}
		catch (IllegalArgumentException | NullPointerException ex) {
			throw invalid();
		}
	}

	private static InvalidBearerTokenException invalid() {
		return new InvalidBearerTokenException("The access token is no longer valid");
	}

}
