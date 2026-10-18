package com.ballastlane.pokemon.handler;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.json.JsonMapper;

/**
 * Answers 401 and 403 from the security filter chain as Problem Details, like every other error of the API.
 * <p>
 * A 401 always carries an RFC 6750 {@code WWW-Authenticate: Bearer} challenge, with {@code error="invalid_token"}
 * when a token was sent but rejected. The reason is never detailed, and nothing from the token is echoed.
 */
public class ProblemDetailsSecurityHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

	private static final Logger log = LoggerFactory.getLogger(ProblemDetailsSecurityHandler.class);

	private final JsonMapper jsonMapper;

	public ProblemDetailsSecurityHandler(JsonMapper jsonMapper) {
		this.jsonMapper = jsonMapper;
	}

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException exception) throws IOException {
		boolean tokenRejected = exception instanceof OAuth2AuthenticationException;
		log.debug("401 for {} {}: {}", request.getMethod(), request.getRequestURI(),
				tokenRejected ? "token rejected" : "no token");
		response.setHeader(HttpHeaders.WWW_AUTHENTICATE, tokenRejected
				? "Bearer error=\"invalid_token\", error_description=\"The access token is invalid or has expired\""
				: "Bearer");
		write(request, response, HttpStatus.UNAUTHORIZED, "Authentication required", tokenRejected
				? "The access token is invalid or has expired; refresh it or sign in again"
				: "This operation needs a bearer access token; sign in at /api/v1/auth/login");
	}

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response,
			AccessDeniedException exception) throws IOException {
		log.debug("403 for {} {}", request.getMethod(), request.getRequestURI());
		write(request, response, HttpStatus.FORBIDDEN, "Forbidden",
				"Your account doesn't have the role this operation needs");
	}

	private void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String title,
			String detail) throws IOException {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setTitle(title);
		problem.setInstance(URI.create(request.getRequestURI()));
		response.setStatus(status.value());
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		jsonMapper.writeValue(response.getOutputStream(), problem);
	}

}
