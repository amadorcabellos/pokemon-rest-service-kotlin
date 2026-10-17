package com.ballastlane.poke.web;

import com.ballastlane.poke.exception.PokeApiException;
import com.ballastlane.poke.exception.PokemonNotFoundException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

/**
 * Maps domain exceptions to RFC 9457 problem details.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(PokemonNotFoundException.class)
	ProblemDetail handleNotFound(PokemonNotFoundException ex) {
		return problem(HttpStatus.NOT_FOUND, "Pokemon not found", ex.getMessage());
	}

	@ExceptionHandler(PokeApiException.class)
	ProblemDetail handleUpstreamFailure(PokeApiException ex) {
		log.error("PokeAPI call failed", ex);
		return problem(HttpStatus.BAD_GATEWAY, "Upstream service error", "PokeAPI is currently unavailable");
	}

	@ExceptionHandler(CallNotPermittedException.class)
	ProblemDetail handleCircuitOpen(CallNotPermittedException ex) {
		log.warn("Rejected PokeAPI call: {}", ex.getMessage());
		return problem(HttpStatus.SERVICE_UNAVAILABLE, "Service temporarily unavailable",
				"PokeAPI is failing; requests are paused for a short time. Please retry later");
	}

	@ExceptionHandler(RequestNotPermitted.class)
	ProblemDetail handleRateLimited(RequestNotPermitted ex) {
		log.warn("Rejected PokeAPI call: {}", ex.getMessage());
		return problem(HttpStatus.TOO_MANY_REQUESTS, "Too many requests",
				"The PokeAPI request quota is exhausted. Please retry later");
	}

	@ExceptionHandler(HandlerMethodValidationException.class)
	ProblemDetail handleValidation(HandlerMethodValidationException ex) {
		String detail = ex.getAllErrors().stream()
				.map(error -> error.getDefaultMessage())
				.findFirst()
				.orElse("Invalid request");
		return problem(HttpStatus.BAD_REQUEST, "Invalid request", detail);
	}

	private static ProblemDetail problem(HttpStatus status, String title, String detail) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setTitle(title);
		return problem;
	}

}
