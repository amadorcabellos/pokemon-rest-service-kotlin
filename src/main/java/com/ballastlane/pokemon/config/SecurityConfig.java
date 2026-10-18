package com.ballastlane.pokemon.config;

import com.ballastlane.pokemon.auth.SessionJwtAuthenticationConverter;
import com.ballastlane.pokemon.repository.AuthSessionRepository;
import com.ballastlane.pokemon.handler.ProblemDetailsSecurityHandler;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.BearerTokenErrors;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import tools.jackson.databind.json.JsonMapper;

/**
 * Which routes need a bearer access token, and how tokens are checked.
 * <p>
 * Protection is by method and path. Every route not listed here is public, and on public routes the
 * {@code Authorization} header isn't even read, so a bad token never breaks them. Stateless: no HTTP session, no
 * cookies, no CSRF token.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

	/** Synchronization: {@code ADMIN} only. */
	static final RequestMatcher ADMIN_ROUTES = new OrRequestMatcher(
			route(HttpMethod.GET, "/api/v1/admin/sync/runs/*"),
			route(HttpMethod.POST, "/api/v1/admin/sync/runs"),
			route(HttpMethod.POST, "/api/v1/admin/sync/pokemon/*"));

	/** Local data updates: {@code USER} (and so {@code ADMIN}). */
	static final RequestMatcher USER_ROUTES = new OrRequestMatcher(
			route(HttpMethod.PUT, "/api/v1/pokemon/*"),
			route(HttpMethod.PATCH, "/api/v1/pokemon/*"));

	/** Any signed-in user. */
	static final RequestMatcher AUTHENTICATED_ROUTES = route(HttpMethod.GET, "/api/v1/auth/user");

	static final RequestMatcher PROTECTED_ROUTES = new OrRequestMatcher(ADMIN_ROUTES, USER_ROUTES,
			AUTHENTICATED_ROUTES);

	/** Tolerated difference between this server's clock and the one that issued a token. */
	private static final Duration CLOCK_SKEW = Duration.ofSeconds(30);

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, SessionJwtAuthenticationConverter converter,
			ProblemDetailsSecurityHandler problemDetails) throws Exception {
		return http
				.csrf(AbstractHttpConfigurer::disable)
				.formLogin(AbstractHttpConfigurer::disable)
				.httpBasic(AbstractHttpConfigurer::disable)
				.logout(AbstractHttpConfigurer::disable)
				.requestCache(AbstractHttpConfigurer::disable)
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(routes -> routes
						.requestMatchers(ADMIN_ROUTES).hasRole("ADMIN")
						.requestMatchers(USER_ROUTES).hasRole("USER")
						.requestMatchers(AUTHENTICATED_ROUTES).authenticated()
						.anyRequest().permitAll())
				.oauth2ResourceServer(resourceServer -> resourceServer
						.bearerTokenResolver(bearerTokenResolver())
						.jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
						.authenticationEntryPoint(problemDetails)
						.accessDeniedHandler(problemDetails))
				.exceptionHandling(exceptions -> exceptions
						.authenticationEntryPoint(problemDetails)
						.accessDeniedHandler(problemDetails))
				.build();
	}

	/**
	 * Reads the bearer token only on protected routes; elsewhere (including refresh and logout) it's ignored.
	 * Two {@code Authorization} headers are rejected rather than guessing which one counts.
	 */
	static BearerTokenResolver bearerTokenResolver() {
		DefaultBearerTokenResolver delegate = new DefaultBearerTokenResolver();
		return (HttpServletRequest request) -> {
			if (!PROTECTED_ROUTES.matches(request)) {
				return null;
			}
			if (Collections.list(request.getHeaders(HttpHeaders.AUTHORIZATION)).size() > 1) {
				throw new OAuth2AuthenticationException(BearerTokenErrors.invalidToken("Ambiguous credentials"));
			}
			return delegate.resolve(request);
		};
	}

	/** {@code ADMIN} can do everything {@code USER} can. */
	@Bean
	static RoleHierarchy roleHierarchy() {
		return RoleHierarchyImpl.withDefaultRolePrefix().role("ADMIN").implies("USER").build();
	}

	@Bean
	SessionJwtAuthenticationConverter sessionJwtAuthenticationConverter(AuthSessionRepository sessionRepository,
			Clock clock) {
		return new SessionJwtAuthenticationConverter(sessionRepository, clock);
	}

	@Bean
	ProblemDetailsSecurityHandler problemDetailsSecurityHandler(JsonMapper jsonMapper) {
		return new ProblemDetailsSecurityHandler(jsonMapper);
	}

	@Bean
	JwtEncoder jwtEncoder(AuthProperties properties) {
		return new NimbusJwtEncoder(new ImmutableSecret<>(signingKey(properties)));
	}

	@Bean
	JwtDecoder jwtDecoder(AuthProperties properties, Clock clock) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(signingKey(properties))
				.macAlgorithm(MacAlgorithm.HS256)
				.build();
		JwtTimestampValidator expiry = new JwtTimestampValidator(CLOCK_SKEW);
		expiry.setClock(clock);
		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(expiry));
		return decoder;
	}

	/**
	 * PBKDF2 rather than BCrypt: BCrypt only uses the first 72 bytes, and a 128-character Unicode password can be
	 * much longer. Hashes are prefixed with their algorithm, so stronger ones can be added later.
	 */
	@Bean
	PasswordEncoder passwordEncoder() {
		String pbkdf2 = "pbkdf2@SpringSecurity_v5_8";
		return new DelegatingPasswordEncoder(pbkdf2,
				Map.of(pbkdf2, Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8()));
	}

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

	private static SecretKey signingKey(AuthProperties properties) {
		return new SecretKeySpec(properties.jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
	}

	private static RequestMatcher route(HttpMethod method, String pattern) {
		return PathPatternRequestMatcher.withDefaults().matcher(method, pattern);
	}

}
